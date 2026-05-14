package ai.brokk.util;

public final class AsciiUtil {
    private AsciiUtil() {}

    public record CaseInsensitiveLiteral(String literal, String foldedLiteral) {
        public CaseInsensitiveLiteral(String literal) {
            this(literal, foldAscii(literal));
        }

        public boolean containedIn(String haystack) {
            return containsFoldedIgnoreCase(haystack, foldedLiteral);
        }
    }

    public static boolean isAscii(CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            if (!isAscii(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static boolean containsIgnoreCase(String haystack, String needle) {
        return containsFoldedIgnoreCase(haystack, foldAscii(needle));
    }

    private static boolean containsFoldedIgnoreCase(String haystack, String foldedNeedle) {
        if (foldedNeedle.isEmpty()) {
            return true;
        }

        int maxStart = haystack.length() - foldedNeedle.length();
        if (maxStart < 0) {
            return false;
        }

        char first = foldedNeedle.charAt(0);
        for (int start = 0; start <= maxStart; start++) {
            if (toLower(haystack.charAt(start)) == first && regionMatchesFolded(haystack, start, foldedNeedle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAscii(char ch) {
        return ch < 128;
    }

    private static boolean regionMatchesFolded(String haystack, int start, String foldedNeedle) {
        for (int i = 1; i < foldedNeedle.length(); i++) {
            if (toLower(haystack.charAt(start + i)) != foldedNeedle.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static String foldAscii(String text) {
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            chars[i] = toLower(chars[i]);
        }
        return new String(chars);
    }

    private static char toLower(char ch) {
        return ch >= 'A' && ch <= 'Z' ? (char) (ch + ('a' - 'A')) : ch;
    }
}
