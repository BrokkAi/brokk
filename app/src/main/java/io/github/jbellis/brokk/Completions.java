package io.github.jbellis.brokk;

import io.github.jbellis.brokk.analyzer.*;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.autocomplete.ShorthandCompletion;

public class Completions {
    private static final Logger logger = LogManager.getLogger(Completions.class);

    public static List<CodeUnit> completeSymbols(String input, IAnalyzer analyzer) {
        String pattern = input.trim();
        if (pattern.length() < 2) {
            return List.of();
        }

        List<CodeUnit> allDefs;
        try {
            // Try index-based searches first (cheap). If they return nothing, fall back to a more permissive
            // searches
            allDefs = tryIndexSearches(input, analyzer);
        } catch (Exception e) {
            logger.warn("Failed to search definitions for autocomplete: {}", e.getMessage());
            // fixme: Not ideal to call this for large projects
            allDefs = analyzer.getAllDeclarations();
        }

        // If the user typed a trailing dot (e.g. "Do."), interpret that as "the short name ends here"
        // and resolve candidates to the class/namespace and its direct members (methods/fields).
        // For trailing-dot queries we return the class and its members directly (no fuzzy scoring),
        // so that "Do." yields the class "Do" and its members like "foo", "bar".
        boolean trailingDot = pattern.endsWith(".");
        if (trailingDot) {
            String baseShort = pattern.substring(0, pattern.length() - 1);
            // Resolve candidates using index queries and fallback logic.
            allDefs = findCandidatesForTrailingDot(analyzer, baseShort);
            // Return class + members immediately (deduplicated), capped to 100 results.
            return allDefs.stream().distinct().limit(100).toList();
        }

        // Create matcher from the effective pattern (without trailing dot if present).
        var matcher = new FuzzyMatcher(pattern);
        // If trailing dot was used, we specifically want to match short names (identifiers) only.
        boolean hierarchicalQuery = (pattern.indexOf('.') >= 0 || pattern.indexOf('$') >= 0);

        // has a family resemblance to scoreShortAndLong but different enough that it doesn't fit
        record ScoredCU(CodeUnit cu, int score) { // Renamed local record to avoid conflict
        }
        return allDefs.stream()
                .map(cu -> {
                    int score;
                    if (hierarchicalQuery) {
                        // query includes hierarchy separators -> match against full FQN
                        score = matcher.score(cu.fqName());
                    } else {
                        // otherwise match ONLY the trailing symbol (class, method, field)
                        score = matcher.score(cu.identifier());
                    }
                    return new ScoredCU(cu, score);
                })
                .filter(sc -> sc.score() != Integer.MAX_VALUE)
                .sorted(Comparator.<ScoredCU>comparingInt(ScoredCU::score)
                        .thenComparing(sc -> sc.cu().fqName()))
                .distinct()
                .limit(100)
                .map(ScoredCU::cu)
                .toList();
    }

    /** Try a series of progressively more permissive index queries before falling back to getAllDeclarations(). */
    private static List<CodeUnit> tryIndexSearches(String input, IAnalyzer analyzer) {
        var trimmed = input.trim();
        if (trimmed.isEmpty()) {
            return Collections.emptyList();
        }

        // 1) Prefer a quoted, case-insensitive substring search first (handles special chars safely).
        var result = analyzer.searchDefinitions("(?i).*" + Pattern.quote(trimmed) + ".*").stream()
                .limit(5000)
                .toList();
        if (!result.isEmpty()) {
            return result;
        }

        // 2) If the query is short, try a "split-char" fuzzy pattern that matches the characters in order
        //    with arbitrary stuff in between. This helps camelCase/snake_case short queries (e.g. "cc" -> .*c.*c.*).
        //    Use Pattern.quote for each character to avoid accidental regex injection.
        if (trimmed.length() < 5) {
            var sb = new StringBuilder("(?i).*");
            for (int i = 0; i < trimmed.length(); i++) {
                char ch = trimmed.charAt(i);
                // skip whitespace in the small pattern construction
                if (Character.isWhitespace(ch)) continue;
                sb.append(Pattern.quote(Character.toString(ch))).append(".*");
            }
            String fuzzyPattern = sb.toString();
            try {
                result = analyzer.searchDefinitions(fuzzyPattern).stream()
                        .limit(5000)
                        .toList();
                if (!result.isEmpty()) {
                    return result;
                }
            } catch (Exception ignored) {
                // If the index doesn't like this pattern, ignore and continue to more permissive fallback.
            }
        }

        // 3) Try an unquoted case-insensitive substring search as a permissive index query.
        try {
            result = analyzer.searchDefinitions("(?i).*" + trimmed + ".*").stream()
                    .limit(5000)
                    .toList();
            if (!result.isEmpty()) {
                return result;
            }
        } catch (Exception ignored) {
            // fall through to expensive fallback
        }

        // 4) Return empty list otherwise
        return Collections.emptyList();
    }

    /** Expand paths that may contain wildcards (*, ?), returning all matches. */
    public static List<BrokkFile> expandPath(IProject project, String pattern) {
        var paths = expandPatternToPaths(project, pattern);
        var root = project.getRoot().toAbsolutePath().normalize();
        return paths.stream()
                .map(p -> {
                    var abs = p.toAbsolutePath().normalize();
                    if (abs.startsWith(root)) {
                        return (BrokkFile) new ProjectFile(root, root.relativize(abs));
                    } else {
                        return (BrokkFile) new ExternalFile(abs);
                    }
                })
                .toList();
    }

    public static BrokkFile maybeExternalFile(Path root, String pathStr) {
        Path p = Path.of(pathStr);
        if (!p.isAbsolute()) {
            return new ProjectFile(root, p);
        }
        if (!p.startsWith(root)) {
            return new ExternalFile(p);
        }
        // we have an absolute path that's part of the project
        return new ProjectFile(root, root.relativize(p));
    }

    /**
     * Expand a path or glob pattern into concrete file Paths. - Supports absolute and relative inputs. - Avoids
     * constructing Path from strings containing wildcards (Windows-safe). - Returns only regular files that exist.
     */
    public static List<Path> expandPatternToPaths(IProject project, String pattern) {
        var trimmed = pattern.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }

        boolean hasGlob = trimmed.indexOf('*') >= 0 || trimmed.indexOf('?') >= 0;
        var sepChar = java.io.File.separatorChar;
        var root = project.getRoot().toAbsolutePath().normalize();

        if (!hasGlob) {
            // Exact path (no wildcards)
            if (looksAbsolute(trimmed)) {
                try {
                    var p = Path.of(trimmed).toAbsolutePath().normalize();
                    return Files.isRegularFile(p) ? List.of(p) : List.of();
                } catch (Exception e) {
                    return List.of();
                }
            } else {
                var p = root.resolve(trimmed).toAbsolutePath().normalize();
                return Files.isRegularFile(p) ? List.of(p) : List.of();
            }
        }

        // Globbing path
        int star = trimmed.indexOf('*');
        int ques = trimmed.indexOf('?');
        int firstWildcard = (star == -1) ? ques : (ques == -1 ? star : Math.min(star, ques));

        int lastSepBefore = -1;
        for (int i = firstWildcard - 1; i >= 0; i--) {
            char c = trimmed.charAt(i);
            if (c == '/' || c == '\\') {
                lastSepBefore = i;
                break;
            }
        }
        String basePrefix = lastSepBefore >= 0 ? trimmed.substring(0, lastSepBefore + 1) : "";

        Path baseDir;
        if (looksAbsolute(trimmed)) {
            if (!basePrefix.isEmpty()) {
                baseDir = Path.of(basePrefix);
            } else if (trimmed.startsWith("\\\\")) {
                // UNC root without server/share is not walkable; require at least \\server\share\
                return List.of();
            } else if (trimmed.length() >= 2 && Character.isLetter(trimmed.charAt(0)) && trimmed.charAt(1) == ':') {
                baseDir = Path.of(Character.toString(trimmed.charAt(0)) + ":\\");
            } else {
                baseDir = Path.of(java.io.File.separator);
            }
        } else {
            var baseRel = basePrefix.replace('/', sepChar).replace('\\', sepChar);
            baseDir = root.resolve(baseRel);
        }

        if (!Files.isDirectory(baseDir)) {
            return List.of();
        }

        // Using matcher relative to baseDir; no absolute glob construction needed here.

        // Determine how deep to walk:
        // - If pattern uses **, search recursively.
        // - Otherwise, limit to the number of remaining path segments after the base prefix.
        boolean recursive = trimmed.contains("**");
        String remainder = lastSepBefore >= 0 ? trimmed.substring(lastSepBefore + 1) : trimmed;
        int remainingSeparators = 0;
        for (int i = 0; i < remainder.length(); i++) {
            char c = remainder.charAt(i);
            if (c == '/' || c == '\\') remainingSeparators++;
        }
        int maxDepth = recursive ? Integer.MAX_VALUE : (1 + remainingSeparators);

        // Build a matcher relative to baseDir to avoid Windows absolute glob quirks.
        String relGlob = remainder.replace('/', sepChar).replace('\\', sepChar);
        var matcher = FileSystems.getDefault().getPathMatcher("glob:" + relGlob);

        try (var stream = Files.walk(baseDir, maxDepth)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> matcher.matches(baseDir.relativize(p)))
                    .map(Path::toAbsolutePath)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean looksAbsolute(String s) {
        if (s.startsWith("/")) {
            return true;
        }
        if (s.startsWith("\\\\")) { // UNC path
            return true;
        }
        return s.length() >= 3
                && Character.isLetter(s.charAt(0))
                && s.charAt(1) == ':'
                && (s.charAt(2) == '\\' || s.charAt(2) == '/');
    }

    /**
     * Helper: resolve candidates for queries ending with a dot (e.g. "Do."). Try index queries first (exact end-of-FQN
     * match, then permissive substring + filter), then only call getAllDeclarations() as a last resort. When we find a
     * class that matches the short name, include its members as well so that "Do." yields the class "Do" and its
     * members ("foo","bar", etc.).
     */
    private static List<CodeUnit> findCandidatesForTrailingDot(IAnalyzer analyzer, String baseShort) {
        if (baseShort.isEmpty()) {
            return List.of();
        }

        // 1) Prefer exact FQN ending with ".<baseShort>" via index
        try {
            var exact = analyzer.searchDefinitions(".*\\." + Pattern.quote(baseShort)).stream()
                    .limit(5000)
                    .toList();
            if (!exact.isEmpty()) {
                return includeMembersForClasses(analyzer, exact);
            }
        } catch (Exception ignored) {
            // fallthrough to next attempt
        }

        // 2) Try a permissive substring index query but filter by identifier to ensure we only
        //    return declarations whose short name equals baseShort.
        try {
            var filtered = analyzer.searchDefinitions("(?i).*" + Pattern.quote(baseShort) + ".*").stream()
                    .limit(5000)
                    .filter(cu -> cu.identifier().equalsIgnoreCase(baseShort))
                    .toList();
            if (!filtered.isEmpty()) {
                return includeMembersForClasses(analyzer, filtered);
            }
        } catch (Exception ignored) {
            // fallback to empty return
        }
        return Collections.emptyList();
    }

    // Given a list of class declarations, return a list containing the classes followed by their members.
    private static List<CodeUnit> includeMembersForClasses(IAnalyzer analyzer, List<CodeUnit> classes) {
        var builder = new java.util.ArrayList<CodeUnit>(classes.size() * 2);
        builder.addAll(classes);
        for (var cls : classes) {
            try {
                var members = analyzer.getMembersInClass(cls.fqName());
                if (!members.isEmpty()) {
                    builder.addAll(members);
                }
            } catch (Exception ignored) {
                // ignore member lookup failures and continue
            }
        }
        return java.util.List.copyOf(builder);
    }

    private record ScoredItem<T>(T source, int score, int tiebreakScore, boolean isShort) { // Renamed to avoid conflict
    }

    public static <T> List<ShorthandCompletion> scoreShortAndLong(
            String pattern,
            Collection<T> candidates,
            Function<T, String> extractShort,
            Function<T, String> extractLong,
            Function<T, Integer> tiebreaker,
            Function<T, ShorthandCompletion> toCompletion) {
        var matcher = new FuzzyMatcher(pattern);
        var scoredCandidates = candidates.stream()
                .map(c -> {
                    int shortScore = matcher.score(extractShort.apply(c));
                    int longScore = matcher.score(extractLong.apply(c));
                    int minScore = Math.min(shortScore, longScore);
                    boolean isShort = shortScore <= longScore; // Prefer short match if scores are equal
                    int tiebreak = tiebreaker.apply(c);
                    return new ScoredItem<>(c, minScore, tiebreak, isShort);
                })
                .filter(sc -> sc.score() != Integer.MAX_VALUE)
                .sorted(Comparator.<ScoredItem<T>>comparingInt(ScoredItem::score)
                        .thenComparingInt(ScoredItem::tiebreakScore)
                        .thenComparing(sc -> extractShort.apply(sc.source)))
                .toList();

        // Find the highest score among the "short" matches
        int maxShortScore = scoredCandidates.stream()
                .filter(ScoredItem::isShort)
                .mapToInt(ScoredItem::score)
                .max()
                .orElse(Integer.MAX_VALUE); // If no short matches, keep all long matches

        // Filter out long matches that score worse than the best short match
        return scoredCandidates.stream()
                .filter(sc -> sc.score <= maxShortScore)
                .limit(100)
                .map(sc -> toCompletion.apply(sc.source))
                .toList();
    }
}
