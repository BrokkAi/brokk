package ai.brokk.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jetbrains.annotations.Nullable;

public sealed interface TextMatcher permits TextMatcher.Literal, TextMatcher.Regex {
    String rawPattern();

    MatchCursor cursor(String input, @Nullable String lowerInput);

    default boolean find(String input, @Nullable String lowerInput) {
        return cursor(input, lowerInput).find();
    }

    static TextMatcher compile(String pattern, int flags) {
        if (isLiteralPattern(pattern)) {
            return Literal.create(pattern, flags);
        }
        validateSafeRegex(pattern);
        try {
            return new Regex(pattern, Pattern.compile(pattern, flags));
        } catch (PatternSyntaxException e) {
            return Literal.create(pattern, flags);
        }
    }

    static void validateSafeRegex(String pattern) {
        if (containsBackreference(pattern)) {
            throw new IllegalArgumentException(
                    "regex backreferences are not supported in file-content search because they can cause excessive backtracking");
        }
        if (containsNestedUnboundedQuantifier(pattern)) {
            throw new IllegalArgumentException(
                    "nested unbounded regex quantifiers are not supported in file-content search because they can cause excessive backtracking");
        }
    }

    private static boolean containsBackreference(String pattern) {
        for (int i = 0; i + 1 < pattern.length(); i++) {
            if (pattern.charAt(i) != '\\') {
                continue;
            }
            char next = pattern.charAt(i + 1);
            if (next >= '1' && next <= '9') {
                return true;
            }
            if (next == 'k' && i + 2 < pattern.length() && pattern.charAt(i + 2) == '<') {
                return true;
            }
            if (next == '\\') {
                i++;
            }
        }
        return false;
    }

    private static boolean containsNestedUnboundedQuantifier(String pattern) {
        int groupStart = -1;
        boolean groupHasUnboundedQuantifier = false;
        boolean escaped = false;
        for (int i = 0; i < pattern.length(); i++) {
            char current = pattern.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == '(' && groupStart < 0) {
                groupStart = i;
                groupHasUnboundedQuantifier = false;
                continue;
            }
            if (groupStart < 0) {
                continue;
            }
            if (current == '*' || current == '+') {
                groupHasUnboundedQuantifier = true;
                continue;
            }
            if (current == ')' && i + 1 < pattern.length()) {
                char next = pattern.charAt(i + 1);
                if (groupHasUnboundedQuantifier && (next == '*' || next == '+')) {
                    return true;
                }
                groupStart = -1;
                groupHasUnboundedQuantifier = false;
            }
        }
        return false;
    }

    static boolean isLiteralPattern(String pattern) {
        for (int i = 0; i < pattern.length(); i++) {
            if (".^$*+?{}[]\\|()".indexOf(pattern.charAt(i)) >= 0) {
                return false;
            }
        }
        return true;
    }

    interface MatchCursor {
        boolean find();

        int start();

        int end();

        String group();
    }

    record Literal(String rawPattern, String needle, boolean caseInsensitive) implements TextMatcher {
        private static Literal create(String pattern, int flags) {
            boolean caseInsensitive = (flags & Pattern.CASE_INSENSITIVE) != 0;
            String needle = caseInsensitive ? pattern.toLowerCase(Locale.ROOT) : pattern;
            return new Literal(pattern, needle, caseInsensitive);
        }

        @Override
        public MatchCursor cursor(String input, @Nullable String lowerInput) {
            String haystack = caseInsensitive ? lowerInputOrCompute(input, lowerInput) : input;
            return new LiteralCursor(input, haystack, needle);
        }

        private static String lowerInputOrCompute(String input, @Nullable String lowerInput) {
            return lowerInput == null ? input.toLowerCase(Locale.ROOT) : lowerInput;
        }
    }

    record Regex(String rawPattern, Pattern pattern) implements TextMatcher {
        @Override
        public MatchCursor cursor(String input, @Nullable String lowerInput) {
            return new RegexCursor(pattern.matcher(input), rawPattern);
        }
    }

    final class LiteralCursor implements MatchCursor {
        private final String input;
        private final String haystack;
        private final String needle;
        private int nextStart = 0;
        private int start = -1;
        private int end = -1;

        private LiteralCursor(String input, String haystack, String needle) {
            this.input = input;
            this.haystack = haystack;
            this.needle = needle;
        }

        @Override
        public boolean find() {
            start = haystack.indexOf(needle, nextStart);
            if (start < 0) {
                end = -1;
                return false;
            }
            end = start + needle.length();
            nextStart = Math.max(start + 1, end);
            return true;
        }

        @Override
        public int start() {
            return start;
        }

        @Override
        public int end() {
            return end;
        }

        @Override
        public String group() {
            return input.substring(start, end);
        }
    }

    final class RegexCursor implements MatchCursor {
        private final Matcher matcher;
        private final String rawPattern;

        private RegexCursor(Matcher matcher, String rawPattern) {
            this.matcher = matcher;
            this.rawPattern = rawPattern;
        }

        @Override
        public boolean find() {
            try {
                return matcher.find();
            } catch (StackOverflowError e) {
                throw new AlmostGrep.RegexMatchOverflowException(rawPattern, e);
            }
        }

        @Override
        public int start() {
            return matcher.start();
        }

        @Override
        public int end() {
            return matcher.end();
        }

        @Override
        public String group() {
            return matcher.group();
        }
    }
}
