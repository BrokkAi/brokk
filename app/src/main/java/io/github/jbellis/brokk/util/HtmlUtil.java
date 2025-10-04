package io.github.jbellis.brokk.util;

/** Utility functions for HTML/XML-related string handling. */
public final class HtmlUtil {

    private HtmlUtil() {}

    /**
     * Escapes a string for safe inclusion in XML/HTML text or attribute values. Replaces: &, <, >, ", ' with
     * corresponding entities.
     *
     * @param s input string (may be null)
     * @return escaped string; empty string if input is null or empty
     */
    public static String escapeXml(String s) {
        if (s.isEmpty()) return "";
        StringBuilder out = new StringBuilder((int) (s.length() * 1.1));
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Best-effort HTML to Markdown converter for UI capture/logging. Handles common tags and entities; strips unknown
     * tags.
     */
    public static String convertToMarkdown(String html) {
        if (html.isEmpty()) return "";

        String s = html;

        // Normalize newlines for BR and paragraphs
        s = s.replaceAll("(?is)<br\\s*/?>", "\n");
        s = s.replaceAll("(?is)</p\\s*>", "\n\n");
        s = s.replaceAll("(?is)<p[^>]*>", "");

        // Headings
        for (int i = 6; i >= 1; i--) {
            String tag = "h" + i;
            String hashes = "#".repeat(i);
            s = s.replaceAll("(?is)<" + tag + "[^>]*>(.*?)</" + tag + ">", hashes + " $1\n\n");
        }

        // Bold/strong and italics/emphasis
        s = s.replaceAll("(?is)<(b|strong)[^>]*>(.*?)</\\1>", "**$2**");
        s = s.replaceAll("(?is)<(i|em)[^>]*>(.*?)</\\1>", "_$2_");

        // Inline code and preformatted blocks
        s = s.replaceAll("(?is)<code[^>]*>(.*?)</code>", "`$1`");
        s = s.replaceAll("(?is)<pre[^>]*>", "\n```\n");
        s = s.replaceAll("(?is)</pre>", "\n```\n");

        // Lists
        s = s.replaceAll("(?is)</?(ul|ol)[^>]*>", "\n");
        s = s.replaceAll("(?is)<li[^>]*>\\s*", "- ");
        s = s.replaceAll("(?is)</li>", "\n");

        // Links: <a href="url">text</a> -> [text](url)
        s = s.replaceAll("(?is)<a\\s+[^>]*href\\s*=\\s*\"([^\"]+)\"[^>]*>(.*?)</a>", "[$2]($1)");
        s = s.replaceAll("(?is)<a\\s+[^>]*href\\s*=\\s*'([^']+)'[^>]*>(.*?)</a>", "[$2]($1)");

        // Images: <img alt="text" src="url" .../> -> ![text](url)
        s = s.replaceAll(
                "(?is)<img\\s+[^>]*alt\\s*=\\s*\"([^\"]*)\"[^>]*src\\s*=\\s*\"([^\"]+)\"[^>]*/?>", "![$1]($2)");
        s = s.replaceAll(
                "(?is)<img\\s+[^>]*src\\s*=\\s*\"([^\"]+)\"[^>]*alt\\s*=\\s*\"([^\"]*)\"[^>]*/?>", "![$2]($1)");

        // Strip any remaining tags
        s = s.replaceAll("(?is)<[^>]+>", "");

        // Decode HTML entities
        s = decodeHtmlEntities(s);

        // Cleanup excessive whitespace
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+\\n", "\n");
        s = s.replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }

    private static String decodeHtmlEntities(String s) {
        if (s.indexOf('&') < 0) return s;
        // Named entities
        s = s.replace("&nbsp;", " ");
        s = s.replace("&lt;", "<");
        s = s.replace("&gt;", ">");
        s = s.replace("&amp;", "&");
        s = s.replace("&quot;", "\"");
        s = s.replace("&apos;", "'");
        // Numeric decimal entities
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("&#(\\d+);").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int cp;
            try {
                cp = Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            String rep = new String(Character.toChars(cp));
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        s = sb.toString();

        // Numeric hex entities
        m = java.util.regex.Pattern.compile("&#x([0-9a-fA-F]+);").matcher(s);
        sb = new StringBuffer();
        while (m.find()) {
            int cp;
            try {
                cp = Integer.parseInt(m.group(1), 16);
            } catch (NumberFormatException e) {
                continue;
            }
            String rep = new String(Character.toChars(cp));
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
