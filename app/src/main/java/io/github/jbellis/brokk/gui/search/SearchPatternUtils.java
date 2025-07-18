package io.github.jbellis.brokk.gui.search;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for common search pattern operations.
 */
public final class SearchPatternUtils {

    private SearchPatternUtils() { }

    /**
     * Compiles a search pattern with the appropriate flags.
     */
    public static Pattern compileSearchPattern(String searchText, boolean caseSensitive) {
        return Pattern.compile(
            Pattern.quote(searchText),
            caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
        );
    }

    /**
     * Counts matches of a search pattern in the given text.
     */
    public static int countMatches(String text, String searchText, boolean caseSensitive) {
        if (searchText.trim().isEmpty() || text.isEmpty()) {
            return 0;
        }

        Pattern pattern = compileSearchPattern(searchText, caseSensitive);
        Matcher matcher = pattern.matcher(text);

        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * Finds all match ranges in the given text.
     */
    public static List<int[]> findAllMatches(String text, String searchText, boolean caseSensitive) {
        List<int[]> ranges = new ArrayList<>();
        if (searchText.trim().isEmpty() || text.isEmpty()) {
            return ranges;
        }

        Pattern pattern = compileSearchPattern(searchText, caseSensitive);
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            ranges.add(new int[]{matcher.start(), matcher.end()});
        }
        return ranges;
    }
}
