package ai.brokk.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Narrow recognizer for generated regex shapes that are safe to evaluate as literal contains checks.
 *
 * <p>This is intentionally not a general regex parser. Its job is to recognize the simple definition-search patterns
 * produced by Brokk callers, for example {@code (?i).*?\QFoo\E.*?} or
 * {@code (?i).*?(?:\QFoo\E|\QBar\E).*?}. Any pattern outside that small grammar must return {@link Optional#empty()}
 * so the caller keeps using normal regex matching.
 */
public record RegexLiteralSet(
        List<String> literals,
        boolean caseInsensitive,
        List<AsciiUtil.CaseInsensitiveLiteral> caseInsensitiveLiterals) {
    public RegexLiteralSet {
        literals = List.copyOf(literals);
        caseInsensitiveLiterals = List.copyOf(caseInsensitiveLiterals);
        assert caseInsensitive || caseInsensitiveLiterals.isEmpty();
    }

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
            return Optional.of(create(List.of(substringFilter), true));
        }
        return fromLiteralPattern(regex, false);
    }

    private static Optional<RegexLiteralSet> fromLiteralPattern(String regex, boolean caseInsensitive) {
        // Keep this recognizer conservative: optional contains wrappers, one optional grouping layer, and literal terms
        // separated by '|'. If any term is not a plain or \Q...\E literal, callers fall back to the compiled regex
        // path.
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
        return Optional.of(create(literals, caseInsensitive));
    }

    private static RegexLiteralSet create(List<String> literals, boolean caseInsensitive) {
        var copiedLiterals = List.copyOf(literals);
        var caseInsensitiveLiterals = caseInsensitive
                ? copiedLiterals.stream()
                        .map(AsciiUtil.CaseInsensitiveLiteral::new)
                        .toList()
                : List.<AsciiUtil.CaseInsensitiveLiteral>of();
        return new RegexLiteralSet(copiedLiterals, caseInsensitive, caseInsensitiveLiterals);
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
