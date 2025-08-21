package io.github.jbellis.brokk.difftool.performance;

/**
 * Utility class for detecting files with problematic long lines that may cause performance issues. Centralizes the
 * logic for identifying single/few long line files to avoid code duplication.
 */
public final class LongLineDetector {

    private LongLineDetector() {
        // Utility class - prevent instantiation
    }

    /**
     * Detects if a document is a single/few long line file that may cause performance issues.
     *
     * <p>A file is considered a "long line file" if it has very few lines (≤3) but substantial content, indicating very
     * long individual lines that can cause performance problems, e.ge on diff or to send to LLM. A common example is
     * uglyfied javascript files
     */
    public static boolean isLongLineFile(int numberOfLines, long contentLength) {
        return numberOfLines <= 3 && contentLength > PerformanceConstants.SINGLE_LINE_THRESHOLD_BYTES;
    }
}
