package io.github.jbellis.brokk.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Minimal replacements for utility methods needed by FuzzyMatcher,
 * based on equivalents in com.intellij.openapi.util.text.StringUtil
 */
public class FuzzyMatcherUtil {
    /**
     * Equivalent to Character.toLowerCase
     */
    public static char toLowerCase(char a) {
        return Character.toLowerCase(a);
    }

    /**
     * Equivalent to Character.toUpperCase
     */
    public static char toUpperCase(char a) {
        return Character.toUpperCase(a);
    }

    /**
     * Direct implementation comparing characters case-insensitively.
     */
    public static boolean charsEqualIgnoreCase(char a, char b) {
        return a == b || toUpperCase(a) == toUpperCase(b) || toLowerCase(a) == toLowerCase(b);
    }

    /**
     * Equivalent to Character.isWhitespace
     */
    public static boolean isWhiteSpace(char c) {
        return Character.isWhitespace(c);
    }

    /**
     * Finds the first occurrence of a character within a specific range of a CharSequence.
     */
    public static int indexOf( CharSequence s, char c, int start, int end) {
        start = Math.max(start, 0);
        for (int i = start; i < Math.min(end, s.length()); i++) {
            if (s.charAt(i) == c) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Finds the last occurrence of a character within a specific range of a CharSequence.
     * Based on StringUtilRt.lastIndexOf.
     */
    public static int lastIndexOf( CharSequence s, char c, int start, int end) {
        start = Math.max(start, 0);
        for (int i = Math.min(end, s.length()) - 1; i >= start; i--) {
            if (s.charAt(i) == c) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Tries to find index of a given pattern at the given buffer.
     * Based on CharArrayUtil.indexOf.
     *
     * @param buffer    characters buffer which contents should be checked for the given pattern
     * @param pattern   target characters sequence to find at the given buffer
     * @param fromIndex start index (inclusive). Zero is used if given index is negative
     * @param toIndex   end index (exclusive)
     * @return index of the given pattern at the given buffer if the match is found; {@code -1} otherwise
     */
    public static int indexOf( CharSequence buffer,  CharSequence pattern, int fromIndex, int toIndex) {
        int patternLength = pattern.length();
        if (fromIndex < 0) {
            fromIndex = 0;
        }
        // Correct the limit calculation based on standard indexOf logic
        int limit = Math.min(toIndex, buffer.length()) - patternLength + 1;
        SearchLoop:
        for (int i = fromIndex; i < limit; i++) {
            for (int j = 0; j < patternLength; j++) {
                if (pattern.charAt(j) != buffer.charAt(i + j)) continue SearchLoop;
            }
            return i;
        }
        return -1;
    }

    /**
     * Calls indexOf(buffer, pattern, fromIndex, buffer.length()).
     * Based on CharArrayUtil.indexOf.
     */
    public static int indexOf( CharSequence buffer,  CharSequence pattern, int fromIndex) {
        return indexOf(buffer, pattern, fromIndex, buffer.length());
    }

    /**
     * Finds the index of the first occurrence of a character in a CharSequence,
     * ignoring case, within a specific range.
     */
    public static int indexOfIgnoreCase( CharSequence where, char what, int fromIndex, int endIndex) {
        fromIndex = Math.max(0, fromIndex);
        for (int i = fromIndex; i < Math.min(endIndex, where.length()); i++) {
            if (charsEqualIgnoreCase(where.charAt(i), what)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Implementation based on String#indexOf logic, but case-insensitive.
     * Based on StringUtil.indexOfIgnoreCase.
     */
    public static int indexOfIgnoreCase( CharSequence where,  CharSequence what, int fromIndex) {
        int targetCount = what.length();
        int sourceCount = where.length();

        if (fromIndex >= sourceCount) {
            return targetCount == 0 ? sourceCount : -1;
        }
        if (fromIndex < 0) {
            fromIndex = 0;
        }
        if (targetCount == 0) {
            return fromIndex;
        }

        char first = what.charAt(0);
        int max = sourceCount - targetCount;

        for (int i = fromIndex; i <= max; i++) {
            /* Look for first character. */
            if (!charsEqualIgnoreCase(where.charAt(i), first)) {
                //noinspection StatementWithEmptyBody,WhileLoopSpinsOnField
                while (++i <= max && !charsEqualIgnoreCase(where.charAt(i), first)) ;
            }

            /* Found first character, now look at the rest of v2 */
            if (i <= max) {
                int j = i + 1;
                int end = j + targetCount - 1;
                for (int k = 1; j < end && charsEqualIgnoreCase(where.charAt(j), what.charAt(k)); j++, k++) ;

                if (j == end) {
                    /* Found whole string. */
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * @deprecated Use {@link #indexOfIgnoreCase(CharSequence, char, int, int)}
     */
    @Deprecated
    public static int indexOfIgnoreCase( String where, char what, int fromIndex) {
        return indexOfIgnoreCase((CharSequence) where, what, fromIndex, where.length());
    }

    /**
     * Removes trailing characters specified in the `charsToRemove` string from the end of the input `s`.
     * Based on Strings.trimEnd.
     */
    public static  String trimEnd( String s,  String charsToRemove) {
        int len = s.length();
        if (len == 0) {
            return "";
        }

        int end = len;
        while (end > 0 && charsToRemove.indexOf(s.charAt(end - 1)) != -1) {
            end--;
        }

        return end == len ? s : s.substring(0, end);
    }


    /**
     * Finds the first index of any character from {@code chars} within the specified range of {@code sequence}.
     * Based on Strings.indexOfAny.
     */
    public static int indexOfAny( CharSequence sequence,  String chars, int fromIndex, int endIndex) {
        endIndex = Math.min(endIndex, sequence.length());
        fromIndex = Math.max(0, fromIndex);

        for (int i = fromIndex; i < endIndex; i++) {
            char c = sequence.charAt(i);
            if (chars.indexOf(c) >= 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Checks if any character from {@code chars} is present within the specified range of {@code sequence}.
     * Based on Strings.containsAnyChar.
     */
    public static boolean containsAnyChar( CharSequence sequence,  String chars, int fromIndex, int endIndex) {
        return indexOfAny(sequence, chars, fromIndex, endIndex) >= 0;
    }

    /**
     * Checks if the specified region of the {@code charArray} matches the beginning of the {@code sequence}.
     * Case-sensitive comparison.
     * Based on CharArrayUtil.regionMatches.
     * Note: The original `regionMatches` had multiple overloads. This matches the specific signature used in `MinusculeMatcherImpl`.
     */
    public static boolean regionMatches(char[] charArray, int start, int end, @Nullable CharSequence sequence) {
        if (sequence == null || end - start > sequence.length()) {
            return false;
        }
        for (int i = 0; i < end - start; i++) {
            if (charArray[start + i] != sequence.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks if a character is an ASCII character.
     * Based on Strings.isAscii.
     */
    public static boolean isAscii(char c) {
        return c < 128;
    }

    /**
     * Checks if all characters in a CharSequence are ASCII characters.
     * Based on AsciiUtil.isAscii.
     */
    public static boolean isAscii(@Nullable CharSequence seq) {
        if (seq == null) return true; // Or false? IntelliJ's AsciiUtil returns true for null.
        for (int i = 0; i < seq.length(); i++) {
            if (!isAscii(seq.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Determines if the character at the given index in the text is the start of a "word".
     * Ported from NameUtilCore.isWordStart, omitting Kana/ideograph/hardcoded checks.
     * Word starts include: beginning of the string, chars after separators, uppercase chars after lowercase, digits after non-digits.
     */
    public static boolean isWordStart( String text, int i) {
        if (i == 0) return true;

        char current = text.charAt(i);
        // Performance: Check non-letter/digit first as it's common and cheap
        if (!Character.isLetterOrDigit(current)) {
            return false; // Current char is a separator, not a word start itself
        }

        char prev = text.charAt(i - 1);

        // After a non-letter/non-digit (separator)
        if (!Character.isLetterOrDigit(prev)) {
            return true;
        }

        // CamelHump: lowercase followed by uppercase
        if (Character.isLowerCase(prev) && Character.isUpperCase(current)) {
            return true;
        }

        // Digit start: non-digit followed by digit handled above; handle letter followed by digit
        if (Character.isDigit(current) && !Character.isDigit(prev)) {
            return true;
        }

        // All caps handling: Check if we are at the transition from an all-caps sequence to lowercase
        // Example: "URLMapping" -> 'M' is a word start. "URL" -> 'L' is not.
        if (Character.isUpperCase(current) && Character.isUpperCase(prev)) {
            int nextPos = i + 1;
            // If the *next* char exists and is lowercase, then 'current' is the start of a new word boundary (like 'M' in URLMapping)
            return nextPos < text.length() && Character.isLowerCase(text.charAt(nextPos));
        }

        return false;
    }

    /**
     * Finds the start index of the next "word" in the text after the given start index.
     * Ported from NameUtilCore.nextWord, depends on the simplified `isWordStart`.
     */
    public static int nextWord( String text, int start) {
        if (start >= text.length()) {
            return start;
        }

        int i = start;
        char ch = text.charAt(i);

        // Treat each digit as a separate hump for simplicity, matching MinusculeMatcherImpl internal behavior
        if (Character.isDigit(ch)) {
            return i + 1;
        }

        // If starting on a non-letter/digit, the next word starts right after it
        if (!Character.isLetterOrDigit(ch)) {
            return i + 1;
        }

        // Handle sequences of uppercase letters (e.g., "URL")
        // Find the end of a potential uppercase sequence
        int upperCaseEnd = i;
        while (upperCaseEnd < text.length() && Character.isUpperCase(text.charAt(upperCaseEnd))) {
            upperCaseEnd++;
        }
        // If we found more than one uppercase char...
        if (upperCaseEnd > i + 1) {
            // If it ends the string or is followed by a non-letter, the whole sequence is one word part.
            if (upperCaseEnd == text.length() || !Character.isLetter(text.charAt(upperCaseEnd))) {
                return upperCaseEnd;
            }
            // Otherwise (e.g., "URLMapping"), the last uppercase char belongs to the next word part.
            return upperCaseEnd - 1;
        }

        // General case: move forward until the start of the *next* word is found
        i++; // Move past the starting character
        while (i < text.length()) {
            if (isWordStart(text, i)) {
                break;
            }
            i++;
        }
        return i;
    }
}
