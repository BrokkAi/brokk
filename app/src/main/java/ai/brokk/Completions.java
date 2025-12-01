package ai.brokk;

import ai.brokk.analyzer.BrokkFile;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.ExternalFile;
import ai.brokk.analyzer.IAnalyzer;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.project.IProject;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.autocomplete.ShorthandCompletion;

public class Completions {
    private static final Logger logger = LogManager.getLogger(Completions.class);

    public static List<CodeUnit> completeSymbols(String input, IAnalyzer analyzer) {
        String query = input.trim();
        if (query.length() < 2) {
            return List.of();
        }

        // 1) Fetch candidates from analyzer (with safe fallback)
        List<CodeUnit> candidates = fetchAutocompleteCandidates(query, analyzer);

        // 2) For short-name queries, enrich with parent class candidates if nested members were returned
        if (!isHierarchicalQuery(query) && !candidates.isEmpty()) {
            candidates = enhanceWithParentClasses(query, candidates, analyzer);
        }

        // 3) Score, sort, dedupe-by-FQN (stable), and limit
        return scoreSortDedupeAndLimit(query, candidates);
    }

    private static boolean isHierarchicalQuery(String query) {
        return query.indexOf('.') >= 0 || query.indexOf('$') >= 0;
    }

    private static List<CodeUnit> fetchAutocompleteCandidates(String query, IAnalyzer analyzer) {
        try {
            // getAllDeclarations would not be correct here since it only lists top-level CodeUnits
            return analyzer.autocompleteDefinitions(query).stream().limit(5000).toList();
        } catch (Exception e) {
            // Handle analyzer exceptions (e.g., SchemaViolationException from JoernAnalyzer)
            logger.warn("Failed to search definitions for autocomplete: {}", e.getMessage());
            // Fall back to using top-level declarations only
            return analyzer.getAllDeclarations();
        }
    }

    private static List<CodeUnit> enhanceWithParentClasses(
            String query, List<CodeUnit> candidates, IAnalyzer analyzer) {
        // Preserve insertion order while deduping by FQN
        java.util.LinkedHashMap<String, CodeUnit> dedup = new java.util.LinkedHashMap<>();
        for (CodeUnit cu : candidates) {
            dedup.put(cu.fqName(), cu);
        }
        for (CodeUnit cu : candidates) {
            String id = cu.identifier();
            int dotIdx = id.indexOf('.');
            // Be robust if an identifier ever starts with a dot; treat ".Foo" as having an empty first segment.
            if (dotIdx >= 0) {
                String firstSegment = id.substring(0, dotIdx);
                if (firstSegment.equalsIgnoreCase(query)) {
                    String parentFqn =
                            cu.packageName().isEmpty() ? firstSegment : (cu.packageName() + "." + firstSegment);
                    // Fetch parent definitions and add them to the set
                    var defs = analyzer.getDefinitions(parentFqn);
                    for (CodeUnit def : defs) {
                        dedup.put(def.fqName(), def);
                    }
                }
            }
        }
        return new java.util.ArrayList<>(dedup.values());
    }

    private static List<CodeUnit> scoreSortDedupeAndLimit(String query, List<CodeUnit> candidates) {
        var matcher = new FuzzyMatcher(query);
        boolean hierarchicalQuery = isHierarchicalQuery(query);

        record ScoredCU(CodeUnit cu, int score) {}

        return candidates.stream()
                .map(cu -> {
                    int score = hierarchicalQuery ? matcher.score(cu.fqName()) : matcher.score(cu.identifier());
                    return new ScoredCU(cu, score);
                })
                .filter(sc -> sc.score() != Integer.MAX_VALUE)
                .sorted(Comparator.<ScoredCU>comparingInt(ScoredCU::score)
                        .thenComparing(sc -> sc.cu().fqName()))
                .map(ScoredCU::cu)
                .collect(java.util.stream.Collectors.toMap(
                        CodeUnit::fqName,
                        java.util.function.Function.identity(),
                        (a, b) -> a,
                        java.util.LinkedHashMap::new))
                .values()
                .stream()
                .limit(100)
                .toList();
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
        var sepChar = File.separatorChar;
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
                baseDir = Path.of(File.separator);
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

    private record ScoredItem<T>(T source, int score, int tiebreakScore, boolean isShort) {}

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
                        .thenComparing(sc -> extractShort.apply(sc.source())))
                .toList();

        // Find the lowest (best) score among the "short" matches
        int bestShortScore = scoredCandidates.stream()
                .filter(ScoredItem::isShort)
                .mapToInt(ScoredItem::score)
                .min()
                .orElse(Integer.MAX_VALUE); // If no short matches, keep all long matches

        // Keep only candidates whose score is better than or equal to the best short match
        return scoredCandidates.stream()
                .filter(sc -> sc.score() <= bestShortScore)
                .limit(100)
                .map(sc -> toCompletion.apply(sc.source()))
                .toList();
    }
}
