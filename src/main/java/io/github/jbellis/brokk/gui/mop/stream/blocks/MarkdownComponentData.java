package io.github.jbellis.brokk.gui.mop.stream.blocks;

import io.github.jbellis.brokk.gui.mop.ThemeColors;
import io.github.jbellis.brokk.gui.mop.stream.IncrementalBlockRenderer;
import io.github.jbellis.brokk.gui.search.SearchConstants;
import io.github.jbellis.brokk.difftool.utils.Colors;
import io.github.jbellis.brokk.difftool.utils.ColorUtil;

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
            Point viewPosition = viewport instanceof JViewport ? ((JViewport)viewport).getViewPosition() : null;
            
            // Update content - sanitize HTML entities for Swing's HTML renderer
            var sanitized = IncrementalBlockRenderer.sanitizeForSwing(html);
            editor.setText("<html><body>" + sanitized + "</body></html>");
            
            // Restore scroll position if possible
            if (viewport instanceof JViewport && viewPosition != null) {
                ((JViewport)viewport).setViewPosition(viewPosition);
            }
        }
    }

    /**
     * Creates a JEditorPane for HTML content with base CSS to match the theme.
     */
    private JEditorPane createHtmlPane(boolean isDarkTheme) {
        var htmlPane = new JEditorPane();
        htmlPane.setContentType("text/html");
        DefaultCaret caret = (DefaultCaret) htmlPane.getCaret();
        caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        htmlPane.setEditable(false);
        htmlPane.setAlignmentX(Component.LEFT_ALIGNMENT);
        htmlPane.setText("<html><body></body></html>");

        var bgColor = ThemeColors.getColor(isDarkTheme, "message_background");

        htmlPane.setBackground(bgColor);

        var kit = (HTMLEditorKit) htmlPane.getEditorKit();
        var ss = kit.getStyleSheet();

        // Base background and text color
        var bgColorHex = ColorUtil.toHex(bgColor);
        var textColor = ThemeColors.getColor(isDarkTheme, "chat_text");
        var textColorHex = ColorUtil.toHex(textColor);
        var linkColor = ThemeColors.getColorHex(isDarkTheme, "link_color_hex");

        // Define theme-specific colors
        var borderColor = ThemeColors.getColorHex(isDarkTheme, "border_color_hex");
        // Base typography
        ss.addRule("body { font-family: 'Segoe UI', system-ui, sans-serif; line-height: 1.5; " +
                           "background-color: " + bgColorHex + "; color: " + textColorHex + "; margin: 0; padding-left: 8px; padding-right: 8px; }");

        // Headings
        ss.addRule("h1, h2, h3, h4, h5, h6 { margin-top: 18px; margin-bottom: 12px; " +
                           "font-weight: 600; line-height: 1.25; color: " + textColorHex + "; }");
        ss.addRule("h1 { font-size: 1.5em; border-bottom: 1px solid " + borderColor + "; padding-bottom: 0.2em; }");
        ss.addRule("h2 { font-size: 1.3em; border-bottom: 1px solid " + borderColor + "; padding-bottom: 0.2em; }");
        ss.addRule("h3 { font-size: 1.1em; }");
        ss.addRule("h4 { font-size: 1em; }");

        // Links
        ss.addRule("a { color: " + linkColor + "; text-decoration: none; }");
        ss.addRule("a:hover { text-decoration: underline; }");

        // Paragraphs and lists
        ss.addRule("p, ul, ol { margin-top: 0; margin-bottom: 12px; }");
        ss.addRule("ul, ol { padding-left: 2em; }");
        ss.addRule("li { margin: 0.25em 0; }");
        ss.addRule("li > p { margin-top: 12px; }");

        // Code styling
        ss.addRule("code { font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace; " +
                           "padding: 0.2em 0.4em; margin: 0; font-size: 85%; border-radius: 3px; " +
                           "color: " + linkColor + "; }");

        // Table styling
        ss.addRule("table { border-collapse: collapse; margin: 15px 0; width: 100%; }");
        ss.addRule("table, th, td { border: 1px solid " + borderColor + "; }");
        ss.addRule("th { background-color: " + ThemeColors.getColorHex(isDarkTheme, "code_block_background") + "; " +
                           "padding: 8px; text-align: left; font-weight: 600; }");
        ss.addRule("td { padding: 8px; }");
        ss.addRule("tr:nth-child(even) { background-color: " + ThemeColors.getColorHex(isDarkTheme, "message_background") + "; }");
        ss.addRule("tr:hover { background-color: " + ThemeColors.getColorHex(isDarkTheme, "chat_background") + "; }");
        
        // Search highlighting classes - using same colors as diff tool (Colors.SEARCH and Colors.CURRENT_SEARCH)
        var searchColorHex = ColorUtil.toHex(Colors.SEARCH);
        var currentSearchColorHex = ColorUtil.toHex(Colors.CURRENT_SEARCH);
        
        ss.addRule("." + SearchConstants.SEARCH_HIGHLIGHT_CLASS + " { background-color: " + searchColorHex + "; color: black; }");
        ss.addRule("." + SearchConstants.SEARCH_CURRENT_CLASS + " { background-color: " + currentSearchColorHex + "; color: black; }");

        return htmlPane;
    }
}
