package ai.brokk.util;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

public class HtmlUtil {

    public static String sanitize(String html) {
        return Jsoup.clean(html, Safelist.relaxed());
    }

    /**
     * Converts HTML content to Markdown.
     *
     * <p>NOTE: This is currently a placeholder implementation.
     *
     * @param htmlContent The HTML content to convert.
     * @return A Markdown representation of the HTML content.
     */
    public static String convertToMarkdown(String htmlContent) {
        // Placeholder implementation
        return "<!-- HTML Content (conversion to Markdown pending) -->\n" + htmlContent;
    }
}
