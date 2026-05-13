package ai.brokk.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

public record RegexLiteralSet(List<String> literals, boolean caseInsensitive) {
    public static Optional<RegexLiteralSet> fromContainsPattern(Pattern pattern, @Nullable String substringFilter) {
        String regex = pattern.pattern();
        if (regex.startsWith("(?i)")) {
            return fromLiteralPattern(regex.substring("(?i)".length()), true);
        }

        if ((pattern.flags() & Pattern.CASE_INSENSITIVE) != 0) {
            return fromLiteralPattern(regex, true);
        }
        if (substringFilter != null) {
            if (!AsciiUtil.isAscii(substringFilter)) {
                return Optional.empty();
            }
            return Optional.of(new RegexLiteralSet(List.of(substringFilter), true));
        }
        return fromLiteralPattern(regex, false);
    }

    private static Optional<RegexLiteralSet> fromLiteralPattern(String regex, boolean caseInsensitive) {
        String body = stripContainsWrapper(regex);
        body = stripGroup(body);
        if (body.isEmpty()) {
            return Optional.empty();
        }

        var literals = new ArrayList<String>();
        for (String quoted : body.split("\\|", -1)) {
            var literal = unquoteLiteral(quoted);
            if (literal.isEmpty()) {
                return Optional.empty();
            }
            if (caseInsensitive && !AsciiUtil.isAscii(literal.get())) {
                return Optional.empty();
            }
            literals.add(literal.get());
        }
        return Optional.of(new RegexLiteralSet(List.copyOf(literals), caseInsensitive));
    }

    private static String stripContainsWrapper(String regex) {
        String body = regex;
        if (body.startsWith(".*?")) {
            body = body.substring(".*?".length());
        } else if (body.startsWith(".*")) {
            body = body.substring(".*".length());
        }

        if (body.endsWith(".*?")) {
            body = body.substring(0, body.length() - ".*?".length());
        } else if (body.endsWith(".*")) {
            body = body.substring(0, body.length() - ".*".length());
        }
        return body;
    }

    private static String stripGroup(String body) {
        if (body.startsWith("(?:") && body.endsWith(")")) {
            return body.substring(3, body.length() - 1);
        }
        if (body.startsWith("(") && body.endsWith(")")) {
            return body.substring(1, body.length() - 1);
        }
        return body;
    }

    private static Optional<String> unquoteLiteral(String regex) {
        if (regex.startsWith("\\Q") && regex.endsWith("\\E")) {
            return Optional.of(regex.substring(2, regex.length() - 2));
        }
        if (TextMatcher.isLiteralPattern(regex)) {
            return Optional.of(regex);
        }
        return Optional.empty();
    }
}
