package ai.brokk.util;

public final class AsciiUtil {
    private AsciiUtil() {}

    public static boolean isAscii(CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            if (!isAscii(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static boolean containsIgnoreCase(String haystack, String needle) {
        int maxStart = haystack.length() - needle.length();
        for (int start = 0; start <= maxStart; start++) {
            if (regionMatchesIgnoreCase(haystack, start, needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAscii(char ch) {
        return ch < 128;
    }

    private static boolean regionMatchesIgnoreCase(String haystack, int start, String needle) {
        for (int i = 0; i < needle.length(); i++) {
            if (toLower(haystack.charAt(start + i)) != toLower(needle.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static char toLower(char ch) {
        return ch >= 'A' && ch <= 'Z' ? (char) (ch + ('a' - 'A')) : ch;
    }
}
