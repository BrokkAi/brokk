package io.github.jbellis.brokk.agents;

/**
 * A single relevance scoring task used by batch scoring APIs and intentionally lives in the 'agents' package to avoid
 * coupling with analyzer-specific classes.
 *
 * @param filterDescription description of what we are looking for
 * @param candidateText the text to score against the filter
 */
public record RelevanceTask(String filterDescription, String candidateText) {

    @Override
    public String toString() {
        return "RelevanceTask[filter=" + preview(filterDescription) + ", candidate=" + preview(candidateText) + "]";
    }

    private static String preview(String s) {
        if (s.isEmpty()) return "";
        int limit = 32;
        if (s.length() <= limit) return s;
        return s.substring(0, limit) + "...";
    }
}
