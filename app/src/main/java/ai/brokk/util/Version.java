package ai.brokk.util;

import com.google.common.base.Splitter;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NullMarked;

/**
 * Legacy Version comparator kept for historical uses.  New SemVer utilities preferred for schema/version
 * comparisons.
 */
@NullMarked
public record Version(String versionString) implements Comparable<Version> {

    private enum TokenType {
        NUM,
        ALPHA
    }

    private record Token(TokenType type, String value) {}

    @Override
    public int compareTo(Version other) {
        var ta = tokenize(this.versionString);
        var tb = tokenize(other.versionString);

        int min = Math.min(ta.size(), tb.size());
        for (int i = 0; i < min; i++) {
            var xa = ta.get(i);
            var xb = tb.get(i);

            if (xa.type() != xb.type()) {
                return xa.type() == TokenType.NUM ? 1 : -1;
            }

            int cmp;
            if (xa.type() == TokenType.NUM) {
                cmp = compareNumericStrings(xa.value(), xb.value());
            } else {
                cmp = xa.value().compareToIgnoreCase(xb.value());
            }

            if (cmp != 0) {
                return cmp;
            }
        }

        if (ta.size() == tb.size()) {
            return 0;
        }

        var longer = ta.size() > tb.size() ? ta : tb;
        int sign = ta.size() > tb.size() ? 1 : -1;

        var next = longer.get(min);
        if (next.type() == TokenType.ALPHA) {
            return -sign;
        }

        return sign;
    }

    private static int compareNumericStrings(String a, String b) {
        String aa = stripLeadingZeros(a);
        String bb = stripLeadingZeros(b);

        if (aa.length() != bb.length()) {
            return Integer.compare(aa.length(), bb.length());
        }

        return aa.compareTo(bb);
    }

    private static String stripLeadingZeros(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == '0') {
            i++;
        }
        return i == s.length() ? "0" : s.substring(i);
    }

    private static List<Token> tokenize(String version) {
        var tokens = new ArrayList<Token>();

        String v = version.strip();
        int i = 0;
        while (i < v.length()) {
            char c = v.charAt(i);

            if (Character.isDigit(c)) {
                int j = i + 1;
                while (j < v.length() && Character.isDigit(v.charAt(j))) {
                    j++;
                }
                tokens.add(new Token(TokenType.NUM, v.substring(i, j)));
                i = j;
                continue;
            }

            if (Character.isLetter(c)) {
                int j = i + 1;
                while (j < v.length() && Character.isLetter(v.charAt(j))) {
                    j++;
                }
                tokens.add(new Token(TokenType.ALPHA, v.substring(i, j)));
                i = j;
                continue;
            }

            i++;
        }

        return tokens;
    }

    /**
     * Simple SemVer utility for schema versioning and comparisons.
     *
     * Supports parsing "MAJOR.MINOR.PATCH" optionally followed by pre-release/build metadata,
     * but only major/minor/patch are used for compatibility decisions.
     */
    @NullMarked
    public static final class SemVer implements Comparable<SemVer> {
        private final int major;
        private final int minor;
        private final int patch;
        private final String original;

        private SemVer(int major, int minor, int patch, String original) {
            this.major = major;
            this.minor = minor;
            this.patch = patch;
            this.original = original;
        }

        public static SemVer parse(String text) {
            String s = text.strip();
            // Remove any pre-release or build metadata for numeric parsing
            int idx = s.indexOf('-');
            if (idx >= 0) s = s.substring(0, idx);
            idx = s.indexOf('+');
            if (idx >= 0) s = s.substring(0, idx);
            List<String> parts = Splitter.on('.').splitToList(s);
            int maj = parseOrZero(parts.get(0));
            int min = parts.size() > 1 ? parseOrZero(parts.get(1)) : 0;
            int pat = parts.size() > 2 ? parseOrZero(parts.get(2)) : 0;
            return new SemVer(maj, min, pat, text);
        }

        private static int parseOrZero(String p) {
            try {
                return Integer.parseInt(p);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        public int major() {
            return major;
        }

        public int minor() {
            return minor;
        }

        public int patch() {
            return patch;
        }

        @Override
        public int compareTo(SemVer other) {
            if (this.major != other.major) return Integer.compare(this.major, other.major);
            if (this.minor != other.minor) return Integer.compare(this.minor, other.minor);
            return Integer.compare(this.patch, other.patch);
        }

        @Override
        public String toString() {
            return original;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SemVer other)) return false;
            return this.major == other.major && this.minor == other.minor && this.patch == other.patch;
        }

        @Override
        public int hashCode() {
            int result = Integer.hashCode(major);
            result = 31 * result + Integer.hashCode(minor);
            result = 31 * result + Integer.hashCode(patch);
            return result;
        }
    }
}
