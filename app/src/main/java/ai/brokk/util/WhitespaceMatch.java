package ai.brokk.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of a whitespace-insensitive search in a list of lines.
 *
 * @param startLine The 0-indexed starting line number in the original content.
 * @param matchedText The actual text from the original content that matched (preserving original whitespace).
 */
public record WhitespaceMatch(int startLine, String matchedText) {

    /** @return the non-whitespace characters in `line` */
    private static String nonWhitespace(String line) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (!Character.isWhitespace(c)) {
                result.append(c);
            }
        }
        return result.toString();
    }

    /**
     * Finds all occurrences of {@code targetLines} within {@code originalLines} ignoring whitespace.
     */
    public static List<WhitespaceMatch> findAll(String[] originalLines, String[] targetLines) {
        List<WhitespaceMatch> results = new ArrayList<>();
        if (targetLines.length == 0) {
            return results;
        }

        for (int start = 0; start <= originalLines.length - targetLines.length; start++) {
            boolean match = true;
            StringBuilder matchedLines = new StringBuilder();
            for (int i = 0; i < targetLines.length; i++) {
                String originalNW = nonWhitespace(originalLines[start + i]);
                String targetNW = nonWhitespace(targetLines[i]);
                if (!originalNW.equals(targetNW)) {
                    match = false;
                    break;
                }
                matchedLines.append(originalLines[start + i]);
                if (i < targetLines.length - 1) {
                    matchedLines.append("\n");
                }
            }

            if (match) {
                results.add(new WhitespaceMatch(start, matchedLines.toString()));
            }
        }
        return results;
    }
}
