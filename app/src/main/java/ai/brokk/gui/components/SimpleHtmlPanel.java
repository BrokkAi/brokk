package ai.brokk.gui.components;

import ai.brokk.difftool.utils.ColorUtil;
import ai.brokk.gui.mop.ThemeColors;
import ai.brokk.util.GlobalUiSettings;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import java.awt.Component;
import java.awt.Dimension;
import javax.swing.JEditorPane;
import javax.swing.UIManager;
import javax.swing.text.DefaultCaret;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;

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

        var caret = (DefaultCaret) getCaret();
        caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);

        setAlignmentX(Component.LEFT_ALIGNMENT);
        setText("<html><body></body></html>");

        applyThemeStyles();
    }

    public void setMarkdown(String markdown) {
        var document = PARSER.parse(markdown);
        var html = RENDERER.render(document);
        setHtmlContent(html);
    }

    public void setHtmlContent(String html) {
        var sanitized = sanitizeForSwing(html);
        setText("<html><body>" + sanitized + "</body></html>");
    }

    public void applyThemeStyles() {
        boolean isDarkTheme = UIManager.getBoolean("laf.dark");

        var bgColor = ThemeColors.getColor(isDarkTheme, ThemeColors.MESSAGE_BACKGROUND);
        setBackground(bgColor);

        var kit = (HTMLEditorKit) getEditorKit();
        var ss = new StyleSheet();
        ss.addRule(buildThemeCss(isDarkTheme));
        kit.setStyleSheet(ss);
    }

    public static String buildThemeCss(boolean isDarkTheme) {
        var bgColor = ThemeColors.getColor(isDarkTheme, ThemeColors.MESSAGE_BACKGROUND);

        float editorFontSize = GlobalUiSettings.getEditorFontSize();
        float bodyFontSize = Math.max(14f, editorFontSize + 2f);

        var bgColorHex = ColorUtil.toHex(bgColor);
        var textColor = ThemeColors.getColor(isDarkTheme, ThemeColors.CHAT_TEXT);
        var textColorHex = ColorUtil.toHex(textColor);
        var linkColor = ThemeColors.getColorHex(isDarkTheme, ThemeColors.LINK_COLOR_HEX);
        var borderColor = ThemeColors.getColorHex(isDarkTheme, ThemeColors.BORDER_COLOR_HEX);
        var codeBlockBg = ThemeColors.getColorHex(isDarkTheme, ThemeColors.CODE_BLOCK_BACKGROUND);

        return """
                body { font-family: 'Segoe UI', system-ui, sans-serif; line-height: 1.5;
                  font-size: %spt; width: 100%%; word-wrap: break-word;
                  background-color: %s; color: %s;
                  margin: 0; padding-left: 8px; padding-right: 8px; }

                h1, h2, h3, h4, h5, h6 { margin-top: 18px; margin-bottom: 12px;
                  font-weight: 600; line-height: 1.25; color: %s; }
                h1 { font-size: 1.5em; border-bottom: 1px solid %s; padding-bottom: 0.2em; }
                h2 { font-size: 1.3em; border-bottom: 1px solid %s; padding-bottom: 0.2em; }
                h3 { font-size: 1.1em; }
                h4 { font-size: 1em; }

                a { color: %s; text-decoration: none; }
                a:hover { text-decoration: underline; }

                p, ul, ol { margin-top: 0; margin-bottom: 12px; }
                ul, ol { padding-left: 2em; }
                li { margin: 0.25em 0; }
                li > p { margin-top: 12px; }

                code { font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
                  padding: 0.2em 0.4em; margin: 0; font-size: 85%%; border-radius: 3px;
                  word-wrap: break-word; color: %s; }

                pre { background-color: %s; padding: 12px;
                  border-radius: 6px; overflow-wrap: break-word; white-space: pre-wrap; word-wrap: break-word;
                  margin: 12px 0; }
                pre code { padding: 0; background: none; color: %s; }

                table { border-collapse: collapse; margin: 15px 0; width: 100%%; }
                table, th, td { border: 1px solid %s; }
                th { background-color: %s; padding: 8px; text-align: left; font-weight: 600; }
                td { padding: 8px; }

                blockquote { margin: 12px 0; padding-left: 16px;
                  border-left: 4px solid %s; color: %s; }
                """
                .stripIndent()
                .formatted(
                        bodyFontSize,
                        bgColorHex,
                        textColorHex,
                        textColorHex,
                        borderColor,
                        borderColor,
                        linkColor,
                        linkColor,
                        codeBlockBg,
                        textColorHex,
                        borderColor,
                        codeBlockBg,
                        borderColor,
                        textColorHex);
    }

    public static String sanitizeForSwing(String html) {
        return html.replace("&amp;apos;", "&#39;").replace("&amp;#39;", "&#39;").replace("&apos;", "&#39;");
    }

    @Override
    public void setSize(Dimension d) {
        super.setSize(d);
        super.setSize(d.width, super.getPreferredSize().height);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public Dimension getMaximumSize() {
        var parent = getParent();
        if (parent != null && parent.getWidth() > 0) {
            return new Dimension(parent.getWidth(), Integer.MAX_VALUE);
        }
        return super.getMaximumSize();
    }

    @Override
    public Dimension getPreferredSize() {
        var parent = getParent();
        if (parent != null && parent.getWidth() > 0) {
            super.setSize(parent.getWidth(), Short.MAX_VALUE);
            var pref = super.getPreferredSize();
            return new Dimension(parent.getWidth(), pref.height);
        }
        return super.getPreferredSize();
    }
}
