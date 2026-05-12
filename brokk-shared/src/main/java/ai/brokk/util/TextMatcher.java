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
        try {
            return new Regex(pattern, Pattern.compile(pattern, flags));
        } catch (PatternSyntaxException e) {
            return Literal.create(pattern, flags);
        }
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
