package io.github.jbellis.brokk.util;

import io.github.jbellis.brokk.util.AtomicWrites;
import io.github.jbellis.brokk.util.CssStyleGenerator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.nodes.Element;

import java.nio.file.Path;

/**
 * Utility class for HTML processing and debugging.
 *
 * <h3>Configuration</h3>
 * HTML debug output can be controlled via system property:
 * <ul>
 *   <li>{@code brokk.html.debug} - Enable/disable HTML debug file output (default: false)</li>
 * </ul>
 *
 * Example: {@code -Dbrokk.html.debug=true} to enable debug HTML file generation
 */
public final class HtmlUtil {
    private static final Logger logger = LogManager.getLogger(HtmlUtil.class);

    public static final boolean ENABLE_HTML_DEBUG_OUTPUT = Boolean.parseBoolean(
        System.getProperty("brokk.html.debug", "false"));

    private HtmlUtil() {}

    /**
     * Converts HTML content to Markdown.
     * <p>
     * NOTE: This is currently a placeholder implementation.
     *
     * @param htmlContent The HTML content to convert.
     * @return A Markdown representation of the HTML content.
     */
    public static String convertToMarkdown(String htmlContent) {
        // Placeholder implementation
        return "<!-- HTML Content (conversion to Markdown pending) -->\n" + htmlContent;
    }


    /**
     * Writes HTML debug output from actual rendered Swing components.
     * This shows the exact HTML that Swing is rendering, which is more accurate
     * than reconstructing from the DOM.
     *
     * @param filename the filename for the debug output
     * @param title the title for the HTML page
     * @param extractedHtml the HTML content extracted from Swing components
     */
    public static void writeActualHtml(String filename, String title, String extractedHtml) {
        if (!ENABLE_HTML_DEBUG_OUTPUT) {
            return;
        }

        writeHtmlWithCss(filename, title, extractedHtml);
    }

    public static void writeInitialRenderHtml(String extractedHtml) {
        var title = "Initial Render Debug - Looking for File Badges";
        var content = "<h1>" + title + "</h1>\n" + extractedHtml;
        writeActualHtml("initial-render.html", title, content);
    }

    private static void writeHtmlWithCss(String filename, String title, String content) {
        var html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html>\n<head>\n");
        html.append("<title>").append(title).append("</title>\n");
        html.append("<style>\n");
        // Use dark theme for debug output since it's more readable for debugging
        addCssRules(html, true);
        html.append("</style>\n</head>\n<body>\n");
        html.append(content);
        html.append("\n</body>\n</html>");

        try {
            var htmlFile = Path.of(filename).toAbsolutePath();
            AtomicWrites.atomicOverwrite(htmlFile, html.toString());
            logger.info("HTML debug output written to: {}", htmlFile);
        } catch (Exception e) {
            logger.warn("Failed to write HTML debug file {}: {}", filename, e.getMessage());
        }
    }

    private static void addCssRules(StringBuilder css, boolean isDarkTheme) {
        // Use the shared CSS generator to ensure consistency with MarkdownComponentData
        css.append(CssStyleGenerator.generateCssString(isDarkTheme));
    }
}
