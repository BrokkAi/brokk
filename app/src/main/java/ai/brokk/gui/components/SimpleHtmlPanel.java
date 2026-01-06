package ai.brokk.gui.components;

import ai.brokk.difftool.utils.ColorUtil;
import ai.brokk.gui.mop.ThemeColors;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import java.awt.Component;
import java.awt.Dimension;
import javax.swing.JEditorPane;
import javax.swing.UIManager;
import javax.swing.text.DefaultCaret;
import javax.swing.text.html.HTMLEditorKit;

/**
 * A lightweight panel for rendering markdown as HTML.
 * Uses flexmark for markdown parsing and a styled JEditorPane for display.
 * Much lighter than MarkdownOutputPanel - no web view, no pool, just simple HTML rendering.
 */
public class SimpleHtmlPanel extends JEditorPane {
    
    private static final Parser PARSER;
    private static final HtmlRenderer RENDERER;
    
    static {
        var options = new MutableDataSet();
        PARSER = Parser.builder(options).build();
        RENDERER = HtmlRenderer.builder(options).build();
    }
    
    public SimpleHtmlPanel() {
        setContentType("text/html");
        setEditable(false);
        setOpaque(true);
        
        // Prevent auto-scrolling on content updates
        var caret = (DefaultCaret) getCaret();
        caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        
        setAlignmentX(Component.LEFT_ALIGNMENT);
        setText("<html><body></body></html>");
        
        applyThemeStyles();
    }
    
    /**
     * Sets the content from markdown text, converting to HTML.
     */
    public void setMarkdown(String markdown) {
        var document = PARSER.parse(markdown);
        var html = RENDERER.render(document);
        setHtmlContent(html);
    }
    
    /**
     * Sets the content directly from HTML.
     */
    public void setHtmlContent(String html) {
        var sanitized = sanitizeForSwing(html);
        setText("<html><body>" + sanitized + "</body></html>");
    }
    
    /**
     * Reapply theme styles - call after theme changes.
     */
    public void applyThemeStyles() {
        boolean isDarkTheme = UIManager.getBoolean("laf.dark");
        
        var bgColor = ThemeColors.getColor(isDarkTheme, ThemeColors.MESSAGE_BACKGROUND);
        setBackground(bgColor);
        
        var kit = (HTMLEditorKit) getEditorKit();
        var ss = kit.getStyleSheet();
        
        var bgColorHex = ColorUtil.toHex(bgColor);
        var textColor = ThemeColors.getColor(isDarkTheme, ThemeColors.CHAT_TEXT);
        var textColorHex = ColorUtil.toHex(textColor);
        var linkColor = ThemeColors.getColorHex(isDarkTheme, ThemeColors.LINK_COLOR_HEX);
        var borderColor = ThemeColors.getColorHex(isDarkTheme, ThemeColors.BORDER_COLOR_HEX);
        
        // Base typography
        ss.addRule("body { font-family: 'Segoe UI', system-ui, sans-serif; line-height: 1.5; " +
                   "background-color: " + bgColorHex + "; color: " + textColorHex + "; " +
                   "margin: 0; padding-left: 8px; padding-right: 8px; }");
        
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
        
        // Pre blocks (for fenced code)
        var codeBlockBg = ThemeColors.getColorHex(isDarkTheme, ThemeColors.CODE_BLOCK_BACKGROUND);
        ss.addRule("pre { background-color: " + codeBlockBg + "; padding: 12px; " +
                   "border-radius: 6px; overflow: auto; margin: 12px 0; }");
        ss.addRule("pre code { padding: 0; background: none; color: " + textColorHex + "; }");
        
        // Table styling
        ss.addRule("table { border-collapse: collapse; margin: 15px 0; width: 100%; }");
        ss.addRule("table, th, td { border: 1px solid " + borderColor + "; }");
        ss.addRule("th { background-color: " + codeBlockBg + "; " +
                   "padding: 8px; text-align: left; font-weight: 600; }");
        ss.addRule("td { padding: 8px; }");
        
        // Blockquotes
        ss.addRule("blockquote { margin: 12px 0; padding-left: 16px; " +
                   "border-left: 4px solid " + borderColor + "; color: " + textColorHex + "; }");
    }
    
    /**
     * Sanitizes HTML for Swing's limited HTML renderer.
     */
    private static String sanitizeForSwing(String html) {
        return html
            .replace("&amp;apos;", "&#39;")
            .replace("&amp;#39;", "&#39;")
            .replace("&apos;", "&#39;");
    }
    
    @Override
    public Dimension getPreferredSize() {
        var pref = super.getPreferredSize();
        // Allow horizontal growth but constrain to parent width
        return new Dimension(Math.min(pref.width, Integer.MAX_VALUE), pref.height);
    }
}
