package ai.brokk.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class FilenamePatternMatcher {
    private FilenamePatternMatcher() {}

    public record FilenamePattern(Pattern pattern, boolean glob, boolean matchFullPath) {
        public boolean matches(String filePath) {
            if (!glob) {
                return AlmostGrep.findWithOverflowGuard(pattern, filePath);
            }
            String candidate = matchFullPath ? filePath : basename(filePath);
            return pattern.matcher(candidate).matches();
        }
    }

    public static List<FilenamePattern> compilePatterns(List<String> patterns, int flags) {
        List<String> nonBlank = patterns.stream().filter(p -> !p.isBlank()).toList();
        if (nonBlank.isEmpty()) {
            return List.of();
        }

        List<FilenamePattern> compiled = new ArrayList<>(nonBlank.size());
        for (String pattern : nonBlank) {
            compiled.add(compilePattern(toUnixPath(pattern), pattern, flags));
            if (pattern.contains("\\")) {
                compiled.add(compilePattern(pattern, pattern, flags));
            }
        }
        return List.copyOf(compiled);
    }

    public static Pattern globToRegex(String glob) {
        var regex = new StringBuilder();
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i += 2;
                    if (i < glob.length() && glob.charAt(i) == '/') {
                        regex.append("/?");
                        i++;
                    }
                } else {
                    regex.append("[^/]*");
                    i++;
                }
            } else if (c == '?') {
                regex.append("[^/]");
                i++;
            } else if (".^$+[]{}()|\\".indexOf(c) >= 0) {
                regex.append('\\').append(c);
                i++;
            } else {
                regex.append(c);
                i++;
            }
        }
        return Pattern.compile(regex.toString());
    }

    private static FilenamePattern compilePattern(String regexPattern, String globPattern, int flags) {
        try {
            return new FilenamePattern(Pattern.compile(regexPattern, flags), false, true);
        } catch (PatternSyntaxException e) {
            String normalizedGlob = toUnixPath(globPattern);
            Pattern globRegex = globToRegex(normalizedGlob);
            Pattern compiledGlob = Pattern.compile(globRegex.pattern(), flags);
            return new FilenamePattern(compiledGlob, true, normalizedGlob.contains("/"));
        }
    }

    private static String toUnixPath(String path) {
        return path.replace('\\', '/');
    }

    private static String basename(String path) {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }
}
