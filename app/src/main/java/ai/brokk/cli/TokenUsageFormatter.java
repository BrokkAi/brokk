package ai.brokk.cli;

public final class TokenUsageFormatter {
    private TokenUsageFormatter() {}

    /** Returns a compact single-line summary. */
    public static String format(int input, int cached, int thinking, int output) {
        long total = Math.max(0L, (long) input)
                + Math.max(0L, (long) cached)
                + Math.max(0L, (long) thinking)
                + Math.max(0L, (long) output);
        return "in " + input
                + " | cached " + cached
                + " | think " + thinking
                + " | out " + output
                + " | total " + total;
    }
}
