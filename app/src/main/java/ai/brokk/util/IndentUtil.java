package ai.brokk.util;

/**
 * Utilities for indentation analysis and scaling.
 * Shared by production code and tests to ensure consistent behavior.
 */
public final class IndentUtil {
    private IndentUtil() {
        // utility
    }

    /**
     * Count the number of leading whitespace characters on a single line.
     */
    public static int countLeadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        return i;
    }

    /**
     * Returns the indent delta (relative to baseIndent) of the first non-blank line after the first line.
     * Returns -1 if no such line exists.
     */
    public static int findFirstIndentStep(String[] lines, int baseIndent) {
        for (int i = 1; i < lines.length; i++) {
            var ln = lines[i];
            if (!ln.isBlank()) {
                return countLeadingWhitespace(ln) - baseIndent;
            }
        }
        return -1;
    }

    /**
     * Computes the scaling factor used to normalize replacement indentation relative to the target structure.
     * If either step is not strictly positive (zero or negative), returns 1.0 (no scaling).
     */
    public static double computeIndentScale(int targetIndentStep, int replaceIndentStep) {
        if (targetIndentStep > 0 && replaceIndentStep > 0) {
            return (double) targetIndentStep / replaceIndentStep;
        }
        return 1.0;
    }

    /**
     * Convenience overload: computes the scaling factor by deriving the first indent steps from the given lines.
     */
    public static double computeIndentScale(
            String[] targetLines, String[] replaceLines, int baseTargetIndent, int baseReplaceIndent) {
        int targetIndentStep = findFirstIndentStep(targetLines, baseTargetIndent);
        int replaceIndentStep = findFirstIndentStep(replaceLines, baseReplaceIndent);
        return computeIndentScale(targetIndentStep, replaceIndentStep);
    }

    /**
     * Counts how many leading lines in 'lines' are completely blank (trim().isEmpty()).
     */
    public static int countLeadingBlankLines(String[] lines) {
        int c = 0;
        for (String ln : lines) {
            if (ln.trim().isEmpty()) {
                c++;
            } else {
                break;
            }
        }
        return c;
    }
}
