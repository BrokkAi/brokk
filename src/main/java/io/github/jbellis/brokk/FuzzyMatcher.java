package io.github.jbellis.brokk;

import io.github.jbellis.brokk.util.FList;
import io.github.jbellis.brokk.util.FuzzyMatcherUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;

/**
 * A fuzzy matcher inspired by IntelliJ's MinusculeMatcher. It finds the best
 * subsequence alignment of a pattern within a given name, supporting CamelHump
 * and prioritizing matches at the start and word boundaries.
 * <p>
 * This implementation aims to replicate the core logic and scoring of
 * {@code com.intellij.psi.codeStyle.MinusculeMatcherImpl} for its default behavior
 * (case-insensitive, no hard separators, preferring start matches).
 * Scores are calculated such that lower values indicate better matches.
 */
public class FuzzyMatcher {

    // Public record for representing matched fragments
    public record TextRange(int startOffset, int endOffset) implements Comparable<TextRange> {
        public int getLength() {
            return endOffset - startOffset;
        }

        public int getStartOffset() {
            return startOffset;
        }

        public int getEndOffset() {
            return endOffset;
        }

        public static TextRange from(int start, int length) {
            return new TextRange(start, start + length);
        }

        @Override
        public int compareTo(TextRange other) {
            int cmp = Integer.compare(startOffset, other.startOffset);
            if (cmp == 0) {
                cmp = Integer.compare(endOffset, other.endOffset);
            }
            return cmp;
        }
    }

    /**
     * Weight added to the score if the match starts at the very beginning of the text.
     * Equivalent to PreferStartMatchMatcherWrapper.START_MATCH_WEIGHT, but reduced.
     */
    private static final int START_MATCH_WEIGHT = 2000; // Reduced from 10000

    /**
     * Camel-hump matching is >O(n), so for larger prefixes we fall back to simpler matching to avoid pauses.
     */
    private static final int MAX_CAMEL_HUMP_MATCHING_LENGTH = 100;

    private final char[] patternChars;
    private final String hardSeparators; // Kept for compatibility, hardcoded to ""
    private final boolean hasHumps;
    private final boolean hasDots;
    private final boolean[] isLowerCase;
    private final boolean[] isUpperCase;
    private final boolean[] isWordSeparator;
    private final char[] toUpperCase;
    private final char[] toLowerCase;
    private final char[] meaningfulCharacters;
    private final int minNameLength;

    /**
     * Constructs a matcher for the given pattern using default options
     * (case-insensitive, preferring start matches).
     *
     * @param pattern The pattern to match against names.
     */
    public FuzzyMatcher(@NotNull String pattern) {
        // Equivalent to Strings.trimEnd(pattern, "* ")
        String trimmedPattern = FuzzyMatcherUtil.trimEnd(pattern.trim(), "*");
        this.patternChars = trimmedPattern.toCharArray();
        this.hardSeparators = ""; // Default behavior

        // Precompute character properties for the pattern
        isLowerCase = new boolean[patternChars.length];
        isUpperCase = new boolean[patternChars.length];
        isWordSeparator = new boolean[patternChars.length];
        toUpperCase = new char[patternChars.length];
        toLowerCase = new char[patternChars.length];
        var meaningful = new StringBuilder();

        for (int k = 0; k < patternChars.length; k++) {
            char c = patternChars[k];
            isLowerCase[k] = Character.isLowerCase(c);
            isUpperCase[k] = Character.isUpperCase(c);
            isWordSeparator[k] = isWordSeparator(c);
            toUpperCase[k] = FuzzyMatcherUtil.toUpperCase(c);
            toLowerCase[k] = FuzzyMatcherUtil.toLowerCase(c);
            if (!isWildcard(k)) {
                meaningful.append(toLowerCase[k]);
                meaningful.append(toUpperCase[k]);
            }
        }
        meaningfulCharacters = meaningful.toString().toCharArray();
        minNameLength = meaningfulCharacters.length / 2;

        // Determine pattern characteristics (ignoring leading wildcards)
        int i = 0;
        while (isWildcard(i)) i++;
        hasHumps = hasFlag(i + 1, isUpperCase) && hasFlag(i, isLowerCase);
        hasDots = hasDots(i);
    }

    /**
     * Checks if the pattern contains the specified flag (using the precomputed boolean array)
     * starting from the given index.
     */
    private boolean hasFlag(int start, boolean[] flags) {
        for (int i = start; i < patternChars.length; i++) {
            if (flags[i]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if the pattern contains a dot ('.') starting from the given index.
     */
    private boolean hasDots(int start) {
        for (int i = start; i < patternChars.length; i++) {
            if (patternChars[i] == '.') {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a character is considered a word separator (whitespace, _, -, :, +, .).
     */
    private static boolean isWordSeparator(char c) {
        return FuzzyMatcherUtil.isWhiteSpace(c) || c == '_' || c == '-' || c == ':' || c == '+' || c == '.';
    }

    /**
     * Returns the cleaned pattern string (trimmed and without trailing '*').
     */
    public @NotNull String getPattern() {
        return new String(patternChars);
    }

    /**
     * Checks if the given name matches the pattern.
     *
     * @param name The text to check against the pattern.
     * @return {@code true} if the name matches, {@code false} otherwise.
     */
    public boolean matches(@NotNull String name) {
        return matchingFragments(name) != null;
    }

    /**
     * Calculates a score indicating how well the name matches the pattern.
     * Lower scores indicate better matches. A score of {@link Integer#MAX_VALUE}
     * indicates no match.
     * <p>
     * The score incorporates factors like:
     * <ul>
     *     <li>Match quality (penalties for gaps, case mismatches).</li>
     *     <li>Bonuses for matching at the start or word boundaries (CamelHump, separators).</li>
     *     <li>A large bonus if the match starts at the very beginning of the name.</li>
     * </ul>
     *
     * @param name The text to score against the pattern.
     * @return The matching score, or {@link Integer#MAX_VALUE} if no match exists.
     */
    public int score(@NotNull String name) {
        var fragments = matchingFragments(name);
        if (fragments == null) {
            return Integer.MAX_VALUE; // No match
        }
        if (fragments.isEmpty()) {
            // Empty pattern matches empty string with score 0.
            // Empty pattern vs non-empty string is handled by minNameLength check.
            return name.isEmpty() ? 0 : Integer.MAX_VALUE;
        }

        // Calculate base score using the logic from MinusculeMatcherImpl.matchingDegree
        int degree = calculateScore(name, fragments);

        // Add bonus if the match starts at the beginning (PreferStartMatchMatcherWrapper logic)
        if (fragments.getHead().getStartOffset() == 0) {
            // The original `matchingDegree` returns higher for better. We'll calculate it that way
            // and then invert. The START_MATCH_WEIGHT is a large positive bonus.
            degree += START_MATCH_WEIGHT;
        }

        // Invert the score: Higher internal degree means better match, so return a smaller number.
        // Use negative degree for simplicity and clarity that lower is better, matching test expectations.
        return -degree;
    }

    /**
     * Finds the contiguous fragments of the name that match the pattern.
     * Returns {@code null} if the name cannot be matched.
     * Based on {@code MinusculeMatcherImpl.matchingFragments}.
     */
    @Nullable
    private FList<TextRange> matchingFragments(@NotNull String name) {
        // Basic length check
        if (name.length() < minNameLength) {
            return null;
        }
        // Empty pattern edge case handled in score()
        if (patternChars.length == 0) {
            return name.isEmpty() ? FList.emptyList() : null;
        }

        // Fallback for very long patterns
        if (patternChars.length > MAX_CAMEL_HUMP_MATCHING_LENGTH) {
            return matchBySubstring(name);
        }

        // Check if all meaningful pattern characters can be found in the name
        // (Optimization from original code)
        int patternIndex = 0;
        for (int i = 0; i < name.length() && patternIndex < meaningfulCharacters.length; ++i) {
            char c = name.charAt(i);
            // Check against both lower and upper case versions in meaningfulCharacters
            if (c == meaningfulCharacters[patternIndex] || c == meaningfulCharacters[patternIndex + 1]) {
                patternIndex += 2;
            }
        }
        // If not all meaningful characters were found, no match is possible
        if (patternIndex < minNameLength * 2) {
            return null;
        }

        boolean isAsciiName = FuzzyMatcherUtil.isAscii(name);
        // Start the recursive matching process.
        // Handle patterns that don't start with a wildcard by finding the first
        // potential match of the first non-wildcard character.
        int patternStart = 0;
        while (isWildcard(patternStart)) {
            patternStart++; // Skip initial wildcards in pattern
        }

        // If pattern consisted only of wildcards, it's an empty match.
        if (patternStart == patternChars.length) {
            return FList.emptyList();
        }

        // Find the first potential occurrence in the name of the first non-wildcard pattern char.
        int nameStart = findNextPatternCharOccurrence(name, 0, patternStart, isAsciiName);

        // Begin the matching process, allowing skips up to the first potential match.
        // The 'true' for allowSpecialChars indicates that gaps before the first match are okay.
        return matchSkippingWords(name, patternStart, nameStart, true, isAsciiName);
    }

    /**
     * Simplified matching for very long patterns, falling back to substring search.
     * Based on {@code MinusculeMatcherImpl.matchBySubstring}.
     */
    @Nullable
    private FList<TextRange> matchBySubstring(@NotNull String name) {
        boolean infix = isPatternChar(0, '*');
        var patternWithoutWildcard = filterWildcard(patternChars);
        if (name.length() < patternWithoutWildcard.length) {
            return null;
        }
        if (infix) {
            // Use our util for case-insensitive substring search
            int index = FuzzyMatcherUtil.indexOfIgnoreCase(name, new String(patternWithoutWildcard), 0);
            if (index >= 0) {
                // MinusculeMatcherImpl returns length - 1 range? Seems odd. Let's return full length.
                return FList.singleton(TextRange.from(index, patternWithoutWildcard.length));
            }
            return null;
        }
        // Case-sensitive prefix check (adaptation of regionMatches)
        // The original used CharArrayUtil.regionMatches(patternWithoutWildcard, 0, patternWithoutWildcard.length, name)
        // which implies a case-sensitive check against the start of the name string.
        // Our FuzzyMatcherUtil.regionMatches performs this case-sensitive check.
        if (FuzzyMatcherUtil.regionMatches(patternWithoutWildcard, 0, patternWithoutWildcard.length, name)) {
            return FList.singleton(new TextRange(0, patternWithoutWildcard.length));
        }
        return null;
    }

    /**
     * Creates a char array from the source array, omitting '*' characters.
     * Based on {@code MinusculeMatcherImpl.filterWildcard}.
     */
    private static char[] filterWildcard(char[] source) {
        var buffer = new char[source.length];
        int i = 0;
        for (char c : source) {
            if (c != '*') buffer[i++] = c;
        }
        return Arrays.copyOf(buffer, i);
    }


    /**
     * Calculates the base matching score based on the fragments found.
     * Higher scores mean better matches internally. This score is later
     * potentially augmented by the START_MATCH_WEIGHT and then inverted.
     * Based on {@code MinusculeMatcherImpl.matchingDegree}.
     */
    private int calculateScore(@NotNull String name, @NotNull FList<TextRange> fragments) {
        // fragments is never null or empty here due to checks in score()
        assert !fragments.isEmpty();

        final var first = fragments.getHead();
        boolean startMatch = first.getStartOffset() == 0;

        int matchingCaseScore = 0; // Renamed from matchingCase to avoid confusion
        int patternPos = -1;       // Tracks the index in patternChars corresponding to the last matched name character
        // int gapPenalty = 0; // Removed as per original formula alignment
        int skippedHumps = 0;      // Count of word starts in the name skipped between matched fragments - calculated incrementally
        int nextHumpStart = 0;     // Index of the next potential word start in the name
        boolean humpStartMatchedUpperCase = false; // Tracks if the last word start matched was an uppercase char matched by an uppercase pattern char

        // Iterate through matched fragments and characters within them
        for (var range : fragments) {
            for (int i = range.getStartOffset(); i < range.getEndOffset(); i++) {
                boolean afterGap = i == range.getStartOffset() && !Objects.equals(first, range); // Is this the first char after a gap?
                boolean isHumpStart = false; // Is the current name char a word start?

                // Advance nextHumpStart to find the word start status of the current character 'i'
                while (nextHumpStart <= i) {
                    if (nextHumpStart == i) {
                        isHumpStart = true;
                    } else if (afterGap) {
                        // Increment skipped humps *during* gap traversal, matching original logic
                        skippedHumps++;
                    }
                    nextHumpStart = FuzzyMatcherUtil.nextWord(name, nextHumpStart);
                }

                // Find the corresponding character in the pattern for the current name character
                char nameChar = name.charAt(i);

                // Re-simulate finding patternPos statefully based on nameChar
                // The original used Strings.indexOf(myPattern, c, p + 1, myPattern.length, false); -> Case-sensitive search!
                // This seems counter-intuitive as the overall matching is case-insensitive.
                // Let's stick to the case-sensitive lookup for scoring fidelity for now.
                int currentPatternPos = -1;
                for (int pi = patternPos + 1; pi < patternChars.length; pi++) {
                    if (isWildcard(pi)) continue; // Skip wildcards when finding pattern char for scoring
                    // Original used case-sensitive find. Replicate this.
                    if (patternChars[pi] == nameChar) {
                        // Found first case-sensitive match after previous pos.
                        currentPatternPos = pi;
                        break;
                    }
                    // If case-sensitive fails, allow case-insensitive match (always true for default options).
                    if (charEquals(patternChars[pi], pi, nameChar, true)) {
                        // Found first case-insensitive match. Store it but keep looking for sensitive.
                        if (currentPatternPos < 0) {
                            currentPatternPos = pi;
                        }
                    }
                }

                // Fallback: If no exact match was found case-sensitively, try case-insensitive.
                // This covers cases where nameChar's case doesn't match patternChar, but it should still score.
                if (currentPatternPos < 0) {
                    // Since options = NONE, ignoreCase is effectively always true
                    for (int pi = patternPos + 1; pi < patternChars.length; pi++) {
                        if (isWildcard(pi)) continue;
                        if (charEquals(patternChars[pi], pi, nameChar, true)) {
                            currentPatternPos = pi;
                            break;
                        }
                    }
                }

                // If we couldn't find the char, something is wrong with fragment generation or scoring logic.
                if (currentPatternPos < 0) {
                    // This should ideally not happen if fragments are correct. Skip scoring this char.
                    // Log or assert here? For now, continue to avoid crashing.
                    continue;
                }
                patternPos = currentPatternPos; // Update state for next iteration inside the loop

                // Determine hump start match status *after* patternPos is known for this char i
                if (isHumpStart) {
                    // Did the pattern character corresponding to this hump start match case exactly?
                    humpStartMatchedUpperCase = nameChar == patternChars[patternPos] && isUpperCase[patternPos];
                }

                // Evaluate score contribution of this character match *inside* the loop
                matchingCaseScore += evaluateCaseMatching(patternPos, humpStartMatchedUpperCase, i, afterGap, isHumpStart, nameChar);

            } // End inner loop (character iteration)
        } // End outer loop (fragment iteration)

        // Calculate final score components based on original logic
        int startIndex = first.getStartOffset();
        // Check if the match starts after a hard separator (always false for us with default hardSeparators="")
        boolean afterSeparator = FuzzyMatcherUtil.containsAnyChar(name, hardSeparators, 0, startIndex);
        // Check if the match starts at a word boundary
        boolean wordStart = startIndex == 0 || FuzzyMatcherUtil.isWordStart(name, startIndex);
        // Check if the match ends exactly at the end of the name string
        boolean finalMatch = fragments.getLast().getEndOffset() == name.length();

        // Combine components into the final score (higher is better internally before inversion)
        // Realigned with MinusculeMatcherImpl.matchingDegree formula
        return (wordStart ? 1000 : 0) +     // Word start bonus (restored)
                matchingCaseScore +       // Case matching score (can be negative)
                -fragments.size() +       // Penalty for number of fragments (original formula)
                -skippedHumps * 10 +      // Penalty for skipped humps
                // gapPenalty removed - not in original formula
                (afterSeparator ? 0 : 2) + // Bonus if not after a hard separator (always +2 for us)
                (startMatch ? 1 : 0) +     // Small bonus for starting at 0
                (finalMatch ? 1 : 0);      // Small bonus for matching up to the end
    }

    /**
     * Evaluates the score contribution of a single character match based on case, position, etc.
     * Based on {@code MinusculeMatcherImpl.evaluateCaseMatching}.
     */
    private int evaluateCaseMatching(int patternIndex,
                                     boolean humpStartMatchedUpperCase,
                                     int nameIndex,
                                     boolean afterGap,
                                     boolean isHumpStart,
                                     char nameChar)
    {
        // Pattern has lowercase, but name had uppercase hump start after a gap? Penalty.
        if (afterGap && isHumpStart && isLowerCase[patternIndex]) {
            return -10; // Disprefer when there's a hump but nothing in the pattern indicates the user meant it to be hump
        }

        // Exact case match
        if (nameChar == patternChars[patternIndex]) {
            if (isUpperCase[patternIndex]) {
                // Strongly prefer user's uppercase matching uppercase: they made an effort to press Shift
                return 50;
            }
            // Very start of the name, case matches (original logic tied to valueStartCaseMatch)
            // Simplified: Small bonus for exact match at start index 0.
            if (nameIndex == 0) {
                return 1;
            }
            // Lowercase matches lowercase hump start - indicates user might be typing humps deliberately
            if (isHumpStart) {
                return 1;
            }
            // Other exact case matches - no special bonus/penalty
            return 0;
        }
        // Case mismatch (but still a match due to case-insensitivity)
        else {
            // Mismatch at hump start: Penalty.
            if (isHumpStart) {
                // Disfavor hump starts where pattern letter case doesn't match name case
                return -1;
            }
            // Lowercase in pattern matched uppercase in name *after* a previous exact uppercase hump match? Penalty.
            // This penalizes matching lowercase pattern chars to uppercase letters *within* a word part
            // if the hump *before* it was an exact case (upper->upper) match.
            else if (isLowerCase[patternIndex] && humpStartMatchedUpperCase) {
                // Disfavor lowercase non-humps matching uppercase shortly after an uppercase hump match
                return -1;
            }
        }
        // Default: no bonus/penalty for simple case-insensitive match
        return 0;
    }

// ========================================================================
// Core Recursive Matching Logic (ported from MinusculeMatcherImpl helpers)
// ========================================================================

    /**
     * Checks if the character at the pattern index is a wildcard ('*' or ' ').
     */
    private boolean isWildcard(int patternIndex) {
        return patternIndex >= 0 && patternIndex < patternChars.length &&
                (patternChars[patternIndex] == ' ' || patternChars[patternIndex] == '*');
    }

    /**
     * Checks if the character at the pattern index matches the given character 'c'.
     */
    private boolean isPatternChar(int patternIndex, char c) {
        return patternIndex >= 0 && patternIndex < patternChars.length && patternChars[patternIndex] == c;
    }

    /**
     * Main recursive matching function. Handles wildcards and delegates to fragment matching.
     * Based on {@code MinusculeMatcherImpl.matchWildcards}.
     */
    @Nullable
    private FList<TextRange> matchWildcards(@NotNull String name, int patternIndex, int nameIndex, boolean isAsciiName) {
        // Base case: End of pattern reached
        if (patternIndex == patternChars.length) {
            return FList.emptyList();
        }
        // Base case: Ran out of name characters to match or invalid index
        if (nameIndex < 0 || nameIndex > name.length()) {
            return null;
        }

        // If current pattern char is NOT a wildcard, match a fragment from here
        if (!isWildcard(patternIndex)) {
            return matchFragment(name, patternIndex, nameIndex, isAsciiName);
        }

        // Current pattern char IS a wildcard. Skip all consecutive wildcards.
        do {
            patternIndex++;
        } while (isWildcard(patternIndex));

        // If wildcards were the end of the pattern
        if (patternIndex == patternChars.length) {
            // Original Trailing space logic:
            // if (isTrailingSpacePattern() && nameIndex != name.length() && (patternIndex < 2 || !isUpperCaseOrDigit(myPattern[patternIndex - 2]))) { ... }
            // Simplified: If pattern ends in wildcard(s), it matches the rest of the name. Return empty list.
            return FList.emptyList();
            // Note: The original logic for trailing spaces seems complex and potentially specific to certain scenarios (like last word part checks).
            // For a general fuzzy matcher, letting trailing wildcards match anything remaining seems reasonable.
        }

        // We have a non-wildcard pattern character after skipping wildcards.
        // Find the first possible occurrence of this character in the remaining name.
        int nextOccurrence = findNextPatternCharOccurrence(name, nameIndex, patternIndex, isAsciiName);

        // Try matching from this occurrence and subsequent ones, allowing skips.
        return matchSkippingWords(name, patternIndex, nextOccurrence, true, isAsciiName);
    }

    /**
     * Finds the next occurrence of the pattern character (at patternIndex) in the name,
     * starting the search from nameIndex. Respects case sensitivity options and potential
     * word start requirements (original optimization).
     */
    private int findNextPatternCharOccurrence(@NotNull String name, int nameIndex, int patternIndex, boolean isAsciiName) {
        // Optimization from original: If previous char was not wildcard/separator, only match at word starts.
        // This favors matching "FB" to "FooBar" at F and B, rather than F and some lowercase b later.
        boolean requireWordStart = patternIndex > 0 &&
                !isWildcard(patternIndex - 1) &&
                !isWordSeparator[patternIndex - 1]; // Check the *previous* pattern char

        if (requireWordStart) {
            return indexOfWordStart(name, patternIndex, nameIndex, isAsciiName);
        } else {
            // Standard case-insensitive search from nameIndex
            return indexOfIgnoreCase(name, nameIndex, patternChars[patternIndex], patternIndex, isAsciiName);
        }
    }

    /**
     * Attempts to match a contiguous fragment starting at nameIndex against the pattern starting at patternIndex.
     * Based on {@code MinusculeMatcherImpl.matchFragment}.
     */
    @Nullable
    private FList<TextRange> matchFragment(@NotNull String name, int patternIndex, int nameIndex, boolean isAsciiName) {
        // Find the longest possible contiguous match starting here
        int fragmentLength = maxMatchingFragment(name, patternIndex, nameIndex);
        // If no character matches at the start (nameIndex), fail immediately.
        if (fragmentLength == 0) {
            return null;
        }
        // If we found a matching fragment, try to match the rest of the pattern recursively starting after this fragment.
        return matchInsideFragment(name, patternIndex, nameIndex, isAsciiName, fragmentLength);
    }

    /**
     * Calculates the maximum length of a contiguous fragment of the name (starting at nameIndex)
     * that matches the pattern (starting at patternIndex).
     * Based on {@code MinusculeMatcherImpl.maxMatchingFragment}.
     */
    private int maxMatchingFragment(@NotNull String name, int patternIndex, int nameIndex) {
        // Check if the very first character matches according to rules (case, etc.)
        if (!isFirstCharMatching(name, nameIndex, patternIndex)) {
            return 0;
        }

        int i = 1; // Start checking from the second character of the potential fragment
        boolean ignoreCase = true; // Since options is always NONE
        // Greedily match subsequent characters as long as they match case-insensitively
        while (nameIndex + i < name.length() && patternIndex + i < patternChars.length) {
            char nameChar = name.charAt(nameIndex + i);
            char patternChar = patternChars[patternIndex + i];

            if (!charEquals(patternChar, patternIndex + i, nameChar, ignoreCase)) {
                // Original had a specific check for skipping digits between pattern digits:
                // if (isSkippingDigitBetweenPatternDigits(patternIndex + i, nameChar)) return 0;
                // This seems overly specific and potentially complex to replicate perfectly, omitting for now.
                break; // Stop the fragment here if chars don't match
            }
            i++;
        }
        return i; // Return the length of the matched fragment
    }

    /**
     * Checks if the first character of a potential fragment matches based on case sensitivity rules.
     * Based on {@code MinusculeMatcherImpl.isFirstCharMatching}.
     */
    private boolean isFirstCharMatching(@NotNull String name, int nameIndex, int patternIndex) {
        if (nameIndex >= name.length()) return false; // Cannot match past the end of the name

        boolean ignoreCase = true; // Since options is always NONE
        char patternChar = patternChars[patternIndex];
        char nameChar = name.charAt(nameIndex);

        // Basic check: Do characters match (case-insensitively)?
        return charEquals(patternChar, patternIndex, nameChar, ignoreCase);

        // Original FIRST_LETTER check - skipped as we only support NONE/ALL (effectively)
    }

    /**
     * Recursive helper called after a potential fragment is identified (by maxMatchingFragment).
     * It tries different lengths of this fragment (from longest down to a minimum) and attempts
     * to match the rest of the pattern recursively. It also handles CamelHump improvements.
     * Based on {@code MinusculeMatcherImpl.matchInsideFragment}.
     */
    @Nullable
    private FList<TextRange> matchInsideFragment(@NotNull String name,
                                                 int patternIndex,
                                                 int nameIndex,
                                                 boolean isAsciiName,
                                                 int fragmentLength) // Max length found by maxMatchingFragment
    {
        // Determine minimum required fragment length. Middle matches (after '*') need longer fragments.
        int minFragment = isMiddleMatch(name, patternIndex, nameIndex) ? 3 : 1;

        // Try to improve by finding better CamelHump matches further down in the name
        // e.g., pattern "CU", name "CurrentUser", fragment "Cu" found initially.
        // This checks if "U" can match a later uppercase 'U' in the name.
        var camelHumpRanges = improveCamelHumps(name, patternIndex, nameIndex, isAsciiName, fragmentLength, minFragment);
        if (camelHumpRanges != null) {
            return camelHumpRanges; // Return the improved match if found
        }

        // If no hump improvement, iterate through possible lengths of the current fragment (longest to shortest valid)
        // and find the first one that allows the *rest* of the pattern to match recursively.
        return findLongestMatchingPrefix(name, patternIndex, nameIndex, isAsciiName, fragmentLength, minFragment);
    }

    /**
     * Checks if the current match position looks like a "middle match" (started by a wildcard '*',
     * not at a word start, and followed by a non-wildcard pattern char). Middle matches often
     * require longer fragments to be considered significant.
     * Based on {@code MinusculeMatcherImpl.isMiddleMatch}.
     */
    private boolean isMiddleMatch(@NotNull String name, int patternIndex, int nameIndex) {
        // Check if previous pattern char was wildcard (or beginning of pattern)
        boolean prevWildcard = patternIndex == 0 || isWildcard(patternIndex - 1);
        // Check if next pattern char exists and is not a wildcard
        boolean nextNotWildcard = patternIndex + 1 < patternChars.length && !isWildcard(patternIndex + 1);
        // Check if current name char is a letter/digit and *not* a word start
        boolean nameMiddle = nameIndex < name.length() &&
                Character.isLetterOrDigit(name.charAt(nameIndex)) &&
                !FuzzyMatcherUtil.isWordStart(name, nameIndex);

        return prevWildcard && nextNotWildcard && nameMiddle;
    }

    /**
     * Attempts to find a better match for uppercase pattern characters by looking further ahead
     * for CamelHump boundaries in the name. E.g., If pattern="FB" and name="FooBarBaz", and
     * we initially matched "FooB", this looks ahead for the 'B' in "Baz".
     * Based on {@code MinusculeMatcherImpl.improveCamelHumps}.
     */
    @Nullable
    private FList<TextRange> improveCamelHumps(@NotNull String name,
                                               int patternIndex, // Start index in pattern for current fragment
                                               int nameIndex,    // Start index in name for current fragment
                                               boolean isAsciiName,
                                               int maxFragment, // Max length of fragment initially found
                                               int minFragment) // Min required length for this fragment
    {
        for (int i = minFragment; i < maxFragment; i++) {
            // Consider position `i` within the current fragment.
            // If the pattern char is Uppercase, but the name char is lowercase...
            if (isUppercasePatternVsLowercaseNameChar(name, patternIndex + i, nameIndex + i)) {
                // ...try to find a match for this uppercase pattern char *further ahead* in the name at a word start.
                var ranges = findUppercaseMatchFurther(name, patternIndex + i, nameIndex + i, isAsciiName);
                if (ranges != null) {
                    // If successful, it means the rest of the pattern matched starting from that later word start.
                    // Prepend the current fragment prefix (up to length i) and return the combined ranges.
                    return prependRange(ranges, nameIndex, i);
                }
                // If findUppercaseMatchFurther returned null, this specific improvement didn't work, continue checking.
            }
        }
        return null; // No improvement found by looking ahead for CamelHumps
    }

    /**
     * Checks if pattern char is uppercase but name char is not (or vice versa), indicating case mismatch.
     */
    private boolean isUppercasePatternVsLowercaseNameChar(String name, int patternIndex, int nameIndex) {
        // Ensure indices are valid before accessing arrays/string
        if (patternIndex >= patternChars.length || nameIndex >= name.length()) return false;
        // Check if pattern char is uppercase AND it doesn't match the name char exactly
        // (implies name char must be lowercase if charEquals check passed originally).
        return isUpperCase[patternIndex] && patternChars[patternIndex] != name.charAt(nameIndex);
    }

    /**
     * Helper for improveCamelHumps: Looks for the uppercase pattern character at subsequent word starts.
     */
    @Nullable
    private FList<TextRange> findUppercaseMatchFurther(@NotNull String name,
                                                       int patternIndex, // Index of uppercase pattern char causing mismatch
                                                       int nameIndex, // Index in name where mismatch occurred
                                                       boolean isAsciiName)
    {
        // Find the next word start in the name *after* the current mismatch position.
        int nextWordStart = indexOfWordStart(name, patternIndex, nameIndex + 1, isAsciiName);
        // If found, try to match the rest of the pattern from there (treating the gap like a wildcard skip).
        // This effectively asks: "If we match pattern[patternIndex] at this later word start, can the rest match?"
        return matchWildcards(name, patternIndex, nextWordStart, isAsciiName);
    }

    /**
     * Tries matching the rest of the pattern recursively after consuming a prefix of the
     * current fragment. Iterates from the longest possible prefix length down to the minimum required,
     * returning the first successful match.
     * Based on {@code MinusculeMatcherImpl.findLongestMatchingPrefix}.
     */
    @Nullable
    private FList<TextRange> findLongestMatchingPrefix(@NotNull String name,
                                                       int patternIndex, // Start of pattern fragment
                                                       int nameIndex,    // Start of name fragment
                                                       boolean isAsciiName,
                                                       int fragmentLength, // Max length of current fragment
                                                       int minFragment)   // Min length required
    {
        // Base case optimization: If the current fragment consumes the *entire* remaining pattern, we found a full match.
        if (patternIndex + fragmentLength >= patternChars.length) {
            // Check we haven't gone past the end of the name string
            if (nameIndex + fragmentLength <= name.length()) {
                // Return a list containing just this final fragment
                return FList.singleton(TextRange.from(nameIndex, fragmentLength));
            } else {
                // This should not happen if fragmentLength was calculated correctly by maxMatchingFragment
                return null;
            }
        }

        // Iterate backwards from the full fragment length down to the minimum required length
        int currentPrefixLength = fragmentLength;
        while (currentPrefixLength >= minFragment) {
            FList<TextRange> remainingRanges = null;
            // Calculate the start indices for the *next* part of the pattern and name
            int nextPatternIndex = patternIndex + currentPrefixLength;
            int nextNameIndex = nameIndex + currentPrefixLength;

            // Check boundary conditions for nextNameIndex
            if (nextNameIndex > name.length()) {
                // We've already consumed too much of the name with this prefix length
                currentPrefixLength--;
                continue;
            }

            // If the next pattern character is a wildcard, delegate to matchWildcards
            if (isWildcard(nextPatternIndex)) {
                remainingRanges = matchWildcards(name, nextPatternIndex, nextNameIndex, isAsciiName);
            }
            // Otherwise (next pattern char is not a wildcard)...
            else {
                // ...find the next occurrence of that pattern character in the name, starting after the current prefix.
                int nextOccurrence = findNextPatternCharOccurrence(name, nextNameIndex, nextPatternIndex, isAsciiName);

                // Check for disallowed separators or dots between the end of our prefix and the next occurrence.
                nextOccurrence = checkForSpecialChars(name, nextNameIndex, nextOccurrence, nextPatternIndex);

                if (nextOccurrence >= 0) {
                    // If a valid next occurrence is found, try matching from there, allowing further skips.
                    // Pass allowSpecialChars=false because we just checked the gap.
                    remainingRanges = matchSkippingWords(name, nextPatternIndex, nextOccurrence, false, isAsciiName);
                }
                // If nextOccurrence < 0, remainingRanges stays null.
            }

            // If the recursive call for the remainder succeeded...
            if (remainingRanges != null) {
                // ...prepend the current prefix fragment and return the complete list.
                return prependRange(remainingRanges, nameIndex, currentPrefixLength);
            }

            // If recursive call failed, try a shorter prefix for the current fragment.
            currentPrefixLength--;

            // Optimization from original: skip faster if the next char we skipped was a wildcard?
            // while (i > 0 && isWildcard(patternIndex + i)) i--; // Doesn't seem right here. If the wildcard call failed, we should still try shorter prefixes.
        }
        return null; // No prefix length (from fragmentLength down to minFragment) allowed the rest of the pattern to match
    }

    /**
     * Recursive helper that handles matching after a wildcard or gap. Finds possible occurrences of the
     * *next* non-wildcard pattern character (patternIndex) in the name (starting search from nameIndex)
     * and tries matching fragments from those points.
     * Based on {@code MinusculeMatcherImpl.matchSkippingWords}.
     */
    @Nullable
    private FList<TextRange> matchSkippingWords(@NotNull String name,
                                                final int patternIndex, // The non-wildcard pattern index we are trying to match
                                                int currentNameIndex, // First potential start index in name for patternIndex
                                                boolean allowSpecialChars, // Whether skipping separators/dots is allowed initially (true after wildcard)
                                                boolean isAsciiName)
    {
        // Iterate through potential starting positions in the name for pattern[patternIndex]
        // Note: Removed maxFoundLength optimization as it seemed too aggressive and caused missed matches.
        while (currentNameIndex >= 0 && currentNameIndex < name.length()) { // Ensure currentNameIndex is valid

            // Check if this nameIndex looks like a valid start based on case and word boundary rules
            boolean seemsLikeStart = seemsLikeFragmentStart(name, patternIndex, currentNameIndex);
            // If it seems like a start, find the max matching fragment length from here
            int fragmentLength = seemsLikeStart ? maxMatchingFragment(name, patternIndex, currentNameIndex) : 0;

            // If we found a potential fragment (length > 0)...
            if (fragmentLength > 0) {
                // Try to match the rest of the pattern recursively starting *after* this fragment
                var ranges = matchInsideFragment(name, patternIndex, currentNameIndex, isAsciiName, fragmentLength);
                if (ranges != null) {
                    return ranges; // Success! Found a complete match path.
                }
                // If matchInsideFragment returned null, this path didn't work. Continue searching.
            }

            // Find the *next* possible occurrence of pattern[patternIndex] in the name, starting *after* the current position.
            int nextNameIndex = findNextPatternCharOccurrence(name, currentNameIndex + 1, patternIndex, isAsciiName);

            // Check for disallowed characters (separators/dots) in the gap we are about to skip.
            // This check is only needed if `allowSpecialChars` is false (i.e., we are not immediately following a wildcard).
            if (!allowSpecialChars) {
                nextNameIndex = checkForSpecialChars(name, currentNameIndex + 1, nextNameIndex, patternIndex);
            }

            // Move to the next potential starting point in the name.
            currentNameIndex = nextNameIndex;
        }
        return null; // No match found starting from any potential currentNameIndex onwards.
    }

    /**
     * Checks if a given name index looks like a valid starting point for the pattern
     * character at patternIndex, considering case and word boundaries.
     * Based on {@code MinusculeMatcherImpl.seemsLikeFragmentStart}.
     */
    private boolean seemsLikeFragmentStart(@NotNull String name, int patternIndex, int nameIndex) {
        // Ensure indices are valid before accessing arrays/string
        if (patternIndex >= patternChars.length || nameIndex >= name.length()) {
            return false;
        }
        char patternChar = patternChars[patternIndex];
        char nameChar = name.charAt(nameIndex);

        // If the pattern character is uppercase...
        if (isUpperCase[patternIndex]) {
            // ...it must match either an uppercase character in the name OR the start of a word.
            if (patternChar != nameChar && // Allow exact match (Upper->Upper)
                    !FuzzyMatcherUtil.isWordStart(name, nameIndex)) {
                // Exception: Allow uppercase pattern char to match a lowercase name char
                // *if* the pattern has no humps (e.g., "URL" matching "urlmapping")
                // *and* case sensitivity allows (always true for default options).
                // If the pattern *does* have humps ("URLLoc"), it implies CamelHump matching is expected,
                // so an uppercase pattern char requires an uppercase or word start in the name.
                return !hasHumps; // Uppercase pattern needs uppercase or word start if humps are present in pattern
            }
        }
        // Otherwise (lowercase pattern char, or uppercase pattern char matching uppercase/word-start name char), it seems ok.
        return true;
    }

    /**
     * Checks for disallowed separators or dots between the end of the last match (start)
     * and the beginning of the next potential match (end). Returns -1 if disallowed chars are found,
     * otherwise returns 'end'.
     * Based on {@code MinusculeMatcherImpl.checkForSpecialChars}.
     */
    private int checkForSpecialChars(String name, int start, int end, int patternIndex) {
        if (end < 0) return -1; // No next occurrence found anyway

        // Original logic for hard separators:
        // Hard separators disallowed if pattern doesn't have humps/separators
        // if (!myHasSeparators && !myHasHumps && FuzzyMatcherUtil.containsAnyChar(name, myHardSeparators, start, end)) {
        //     return -1;
        // }
        // Since our hardSeparators is always "", this check is effectively disabled.

        // If the pattern contains dots...
        if (hasDots) {
            // ...and the *previous* pattern char was NOT a dot...
            if (!isPatternChar(patternIndex - 1, '.')) {
                // ...then we are not allowed to skip over dots in the name string in this gap.
                if (FuzzyMatcherUtil.indexOf(name, '.', start, end) != -1) {
                    return -1; // Found a dot in the gap, but pattern didn't have one here. Disallowed.
                }
            }
            // If previous pattern char *was* a dot, we *can* skip dots here (one pattern dot can match multiple name dots).
        }
        return end; // Looks okay, no disallowed characters found in the gap.
    }

// ========================================================================
// Utility Helpers (ported or adapted from MinusculeMatcherImpl/Util)
// ========================================================================

    /**
     * Case-insensitive character comparison, using precomputed lowercase/uppercase versions of pattern chars.
     */
    private boolean charEquals(char patternChar, int patternIndex, char nameChar, boolean ignoreCase) {
        // Exact match is fastest
        if (patternChar == nameChar) return true;
        // If not exact and ignoreCase is false, fail
        if (!ignoreCase) return false;
        // Check against precomputed lowercase and uppercase versions from pattern
        return toLowerCase[patternIndex] == nameChar || toUpperCase[patternIndex] == nameChar;
    }

    /**
     * Prepends a new TextRange to an existing FList of ranges, merging if adjacent.
     * Based on {@code MinusculeMatcherImpl.prependRange}.
     */
    private static @NotNull FList<TextRange> prependRange(@NotNull FList<TextRange> ranges, int from, int length) {
        if (length == 0) return ranges; // Don't prepend empty ranges

        var newRange = TextRange.from(from, length);
        var head = ranges.getHead();

        // Merge if the new range ends exactly where the existing head range begins
        if (head != null && newRange.getEndOffset() == head.getStartOffset()) {
            var mergedRange = new TextRange(newRange.getStartOffset(), head.getEndOffset());
            // Return the tail of the original list with the new merged range prepended
            return ranges.getTail().prepend(mergedRange);
        }
        // Otherwise, just prepend the new range without merging
        return ranges.prepend(newRange);
    }

    /**
     * Finds the index of the first occurrence of the pattern character (at patternIndex)
     * that occurs at a word start boundary in the name, starting the search from 'startFrom'.
     * Based on {@code MinusculeMatcherImpl.indexOfWordStart}.
     */
    private int indexOfWordStart(@NotNull String name, int patternIndex, int startFrom, boolean isAsciiName) {
        final char p = patternChars[patternIndex];
        // Original complex optimization: If pattern is lowercase and has humps, don't match word starts unless prev pattern char was separator.
        // if (myHasHumps && isLowerCase[patternIndex] && !(patternIndex > 0 && isWordSeparator[patternIndex - 1])) { return -1; }
        // Simplified: This seems overly specific. SeemsLikeFragmentStart handles the primary case (uppercase pattern needs word start).
        // Basic boundary check.
        if (startFrom >= name.length()) {
            return -1;
        }

        int currentIndex = startFrom;
        // Treat non-letter/digit pattern chars differently - they don't need a word start boundary.
        boolean isSpecialPatternChar = !Character.isLetterOrDigit(p);

        while (true) {
            // Find the next occurrence of the pattern character, ignoring case.
            currentIndex = indexOfIgnoreCase(name, currentIndex, p, patternIndex, isAsciiName);
            if (currentIndex < 0) return -1; // Not found anywhere further

            // If found, check if it qualifies:
            // - Either the pattern char itself is special (e.g., '.'), OR
            // - The position in the name is a word start.
            if (isSpecialPatternChar || FuzzyMatcherUtil.isWordStart(name, currentIndex)) {
                return currentIndex; // Found a valid word start match
            }

            // If it wasn't a word start, continue searching from the *next* character in the name.
            currentIndex++;
        }
    }

    /**
     * Performs case-insensitive search for a character 'p' (from pattern at patternIndex)
     * in the 'name' string, starting from 'fromIndex'. Uses precomputed case arrays for efficiency,
     * especially for ASCII.
     * Based on {@code MinusculeMatcherImpl.indexOfIgnoreCase}.
     */
    private int indexOfIgnoreCase(String name, int fromIndex, char p, int patternIndex, boolean isAsciiName) {
        // Optimization for ASCII from original MinusculeMatcherImpl
        if (isAsciiName && FuzzyMatcherUtil.isAscii(p)) {
            // Use precomputed uppercase/lowercase versions of the pattern character
            char pUpper = toUpperCase[patternIndex];
            char pLower = toLowerCase[patternIndex];
            for (int i = fromIndex; i < name.length(); i++) {
                char c = name.charAt(i);
                // Check if the name character matches either case of the pattern character
                if (c == pUpper || c == pLower) {
                    return i;
                }
            }
            return -1; // Not found
        }
        // Fallback for non-ASCII or if name is not purely ASCII, using standard utility method
        // This ensures correctness but might be slower.
        return FuzzyMatcherUtil.indexOfIgnoreCase(name, p, fromIndex, name.length());
    }
}
