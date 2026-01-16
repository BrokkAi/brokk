package ai.brokk;

/**
 * Centralized weights and bonuses for symbol and file completion scoring.
 * Scores are generally calculated such that lower values (more negative) indicate better matches.
 */
public final class ScoringConstants {
    private ScoringConstants() {}

    // --- FuzzyMatcher Weights (Inner matching logic) ---

    /**
     * Weight added to the internal matching degree if the pattern matches the entire name (case-insensitively).
     * This is later negated in FuzzyMatcher.score().
     */
    public static final int EXACT_MATCH_BONUS = 5000;

    /**
     * Weight added to the internal matching degree if the match starts at the very beginning of the text.
     * This is later negated in FuzzyMatcher.score().
     */
    public static final int START_MATCH_BONUS = 2000;

    // --- Completions Weights (High-level ranking) ---

    /**
     * Bonus subtracted from the score if the CodeUnit is a CLASS.
     * Classes are generally preferred over members (fields/functions) in top-level completion results.
     */
    public static final int CLASS_TYPE_BONUS = 10000;

    /**
     * Penalty multiplier added to the score based on package depth (number of dots in FQN).
     * Prefer shallower package depths (fewer dots) as tie-breakers.
     */
    public static final int PACKAGE_DEPTH_PENALTY = 10;

    /**
     * Bonus subtracted from the score for ProjectFile completions that match preferred analyzer extensions.
     */
    public static final int PREFERRED_EXTENSION_BONUS = 300;

    /**
     * Tolerance added to the best score when filtering short-name file matches.
     */
    public static final int FILE_SHORT_NAME_TOLERANCE = 300;

    /**
     * Sentinel score used when score computation would overflow.
     * Intentionally set to a high (bad) value so overflowed candidates rank last,
     * not first. This is semantically correct: lower scores are better, so overflow
     * should produce a worst-case score, not a best-case score.
     *
     * Note: overflow should be rare with current constant values. If this sentinel
     * is observed in production (via logging), it signals that constants may need review.
     */
    public static final int OVERFLOW_SCORE_SENTINEL = Integer.MAX_VALUE - 1;

}
