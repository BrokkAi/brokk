package ai.brokk.util;

import ai.brokk.ICodeReview;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;

/**
 * Parses out-of-band code excerpts from LLM responses.
 * Expected format:
 * BRK_EXCERPT_$ID
 * $filename
 * ```[lang]
 * $excerptContent
 * ```
 */
@NullMarked
public class ReviewExcerptParser {

    public static final ReviewExcerptParser instance = new ReviewExcerptParser();

    private static final Pattern EXCERPT_PATTERN = Pattern.compile(
            "^\\s*BRK_EXCERPT_(\\S+)\\s*$\\R" + // Marker and ID
            "^\\s*([^\\r\\n]+?)\\s*$\\R" + // Filename
            "^\\s*```[^\\r\\n]*\\R" + // Opening fence (optional lang)
            "((?:(?!^\\s*BRK_EXCERPT_).)*?)" + // Content, but must not span into the next excerpt header
            "^\\s*```\\s*$", // Closing fence line
            Pattern.MULTILINE | Pattern.DOTALL);

    private ReviewExcerptParser() {}

    /**
     * Parses the provided text for BRK_EXCERPT blocks.
     *
     * @param text The raw response text to parse.
     * @return A map of integer ID to CodeExcerpt.
     */
    public Map<Integer, ICodeReview.CodeExcerpt> parseExcerpts(String text) {
        Map<Integer, ICodeReview.CodeExcerpt> excerpts = new HashMap<>();
        Matcher matcher = EXCERPT_PATTERN.matcher(text);

        while (matcher.find()) {
            String idString = matcher.group(1);
            try {
                int id = Integer.parseInt(idString);
                String filename = matcher.group(2).trim();
                String content = stripSingleTrailingLineBreak(matcher.group(3));

                excerpts.put(id, new ICodeReview.CodeExcerpt(filename, content));
            } catch (NumberFormatException ignored) {
                // If the LLM didn't follow instructions and used a non-numeric ID, we skip it
            }
        }

        return Map.copyOf(excerpts);
    }

    private static String stripSingleTrailingLineBreak(String s) {
        if (s.endsWith("\r\n")) {
            return s.substring(0, s.length() - 2);
        }
        if (s.endsWith("\n") || s.endsWith("\r")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }
}
