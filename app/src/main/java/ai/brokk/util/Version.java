package ai.brokk.util;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NullMarked;

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
}
