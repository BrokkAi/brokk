package ai.brokk.agents;

/**
 * A single relevance scoring task used by batch scoring APIs and intentionally lives in the 'agents' package to avoid
 * coupling with analyzer-specific classes.
 *
 * @param filterDescription description of what we are looking for
 * @param candidateText the text to score against the filter
 */
public record RelevanceTask(String filterDescription, String candidateText, int expectedScoreCount) {

    public RelevanceTask(String filterDescription, String candidateText) {
        this(filterDescription, candidateText, 1);
    }

    @Override
    public String toString() {
        String suffix = expectedScoreCount > 1 ? ", expectedScores=" + expectedScoreCount : "";
        return "RelevanceTask[filter=" + preview(filterDescription) + ", candidate=" + preview(candidateText) + suffix
                + "]";
    }

    private static String preview(String s) {
        if (s.isEmpty()) return "";
        int limit = 32;
        if (s.length() <= limit) return s;
        return s.substring(0, limit) + "...";
    }
}
