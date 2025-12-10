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
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.autocomplete.ShorthandCompletion;

public class Completions {
    private static final Logger logger = LogManager.getLogger(Completions.class);
    private static final int SHORT_TOLERANCE = 300;
    // Prefer analyzer-supported file extensions more strongly when scoring ProjectFile completions.
    private static final int PREFERRED_EXTENSION_PRIORITY_BONUS = 300;

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
            // Identifiers should not normally start with '.', but be robust: treat ".Foo" as having an empty first
            // segment.
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
        return new ArrayList<>(dedup.values());
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
                .collect(Collectors.collectingAndThen(
                        Collectors.toMap(CodeUnit::fqName, Function.identity(), (a, b) -> a, LinkedHashMap::new), m -> {
                            var ordered = new ArrayList<>(m.values());
                            if (ordered.size() > 100) {
                                return ordered.subList(0, 100);
                            }
                            return ordered;
                        }));
    }

    /** Expand paths that may contain wildcards (*, ?), returning all matches. */
    public static List<BrokkFile> expandPath(IProject project, String pattern) {
        var paths = expandPatternToPaths(project, pattern);
        var root = project.getRoot().toAbsolutePath().normalize();
        return paths.stream()
                .map(p -> {
                    var abs = p.toAbsolutePath().normalize();
                    if (abs.startsWith(root)) {
                        return new ProjectFile(root, root.relativize(abs));
                    } else {
                        return new ExternalFile(abs);
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
                baseDir = Path.of(trimmed.charAt(0) + ":\\");
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

    private record ScoredItem<T>(T source, int shortScore, int longScore, int tiebreakScore) {}

    /**
     * Scores candidates using a short and long text for each item. See the overload with {@code minLength}
     * for details on the scoring and filtering policy.
     */
    public static <T> List<ShorthandCompletion> scoreShortAndLong(
            String pattern,
            Collection<T> candidates,
            Function<T, String> extractShort,
            Function<T, String> extractLong,
            Function<T, Integer> tiebreaker,
            Function<T, ShorthandCompletion> toCompletion) {
        return scoreShortAndLong(pattern, candidates, extractShort, extractLong, tiebreaker, toCompletion, 1);
    }

    /**
     * Rank-and-filter helper that scores each candidate twice: once against a short label and once against a long label.
     *
     * Policy:
     * - Compute the best short score among all candidates.
     * - Keep candidates whose short score is within a tolerance window of the best short score
     *   (bestShort + SHORT_TOLERANCE). This preserves near-best short matches (e.g., "Chrome.java" for "Chr").
     * - Also keep candidates whose long score is strictly better than the best short score. This allows a long
     *   form that is an exact or clearly superior match to surface even if its short form is weak.
     * - Do not include long-only matches that are worse than the best short score. This avoids noisy mid-word
     *   matches crowding out good short matches.
     *
     * The fuzzy matcher returns lower scores for better matches; Integer.MAX_VALUE denotes no match.
     * Results are sorted by the better of the two scores, then by the provided tiebreaker and short label.
     *
     * @param minLength minimum trimmed pattern length required to run matching; shorter inputs return no results.
     */
    public static <T> List<ShorthandCompletion> scoreShortAndLong(
            String pattern,
            Collection<T> candidates,
            Function<T, String> extractShort,
            Function<T, String> extractLong,
            Function<T, Integer> tiebreaker,
            Function<T, ShorthandCompletion> toCompletion,
            int minLength) {
        String trimmed = pattern.trim();
        if (trimmed.length() < minLength) {
            return List.of();
        }

        var matcher = new FuzzyMatcher(trimmed);
        var scoredCandidates = candidates.stream()
                .map(c -> {
                    int shortScore = matcher.score(extractShort.apply(c));
                    int longScore = matcher.score(extractLong.apply(c));
                    int tiebreak = tiebreaker.apply(c);
                    return new ScoredItem<>(c, shortScore, longScore, tiebreak);
                })
                .filter(sc -> sc.shortScore() != Integer.MAX_VALUE || sc.longScore() != Integer.MAX_VALUE)
                .sorted(Comparator.<ScoredItem<T>>comparingInt(sc -> Math.min(sc.shortScore(), sc.longScore()))
                        .thenComparingInt(ScoredItem::tiebreakScore)
                        .thenComparing(scoredItem -> extractShort.apply(scoredItem.source())))
                .toList();

        int bestShortScore =
                scoredCandidates.stream().mapToInt(ScoredItem::shortScore).min().orElse(Integer.MAX_VALUE);

        int shortThreshold = bestShortScore == Integer.MAX_VALUE ? Integer.MAX_VALUE : bestShortScore + SHORT_TOLERANCE;

        return scoredCandidates.stream()
                .filter(sc -> (sc.shortScore() <= shortThreshold) || (sc.longScore() < bestShortScore))
                .limit(100)
                .map(sc -> toCompletion.apply(sc.source()))
                .toList();
    }

    /**
     * Rank-and-filter ProjectFile candidates with a preference for files whose extensions are
     * supported by the project's active analyzers. Uses the default minimum pattern length of 1.
     */
    public static List<ShorthandCompletion> scoreProjectFiles(
            String pattern,
            IProject project,
            Collection<ProjectFile> candidates,
            Function<ProjectFile, String> extractShort,
            Function<ProjectFile, String> extractLong,
            Function<ProjectFile, ShorthandCompletion> toCompletion) {
        return scoreProjectFiles(pattern, project, candidates, extractShort, extractLong, toCompletion, 1);
    }

    /**
     * Rank-and-filter ProjectFile candidates with a preference for files whose extensions are
     * supported by the project's active analyzers.
     *
     * Prefers candidates whose {@code ProjectFile.extension()} (lowercased) is present in the union
     * of {@code Language.getExtensions()} from {@code project.getAnalyzerLanguages()} by using a
     * lower tiebreak score for those candidates.
     */
    public static List<ShorthandCompletion> scoreProjectFiles(
            String pattern,
            IProject project,
            Collection<ProjectFile> candidates,
            Function<ProjectFile, String> extractShort,
            Function<ProjectFile, String> extractLong,
            Function<ProjectFile, ShorthandCompletion> toCompletion,
            int minLength) {
        String trimmed = pattern.trim();
        if (trimmed.length() < minLength) {
            return List.of();
        }

        Set<String> preferredExts = project.getAnalyzerLanguages().stream()
                .flatMap(lang -> lang.getExtensions().stream())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        var matcher = new FuzzyMatcher(trimmed);

        record ScoredPF(ProjectFile pf, int shortScore, int longScore, boolean preferred) {}

        var scoredCandidates = candidates.stream()
                .map(pf -> {
                    int shortScore = matcher.score(extractShort.apply(pf));
                    int longScore = matcher.score(extractLong.apply(pf));
                    boolean preferred = preferredExts.contains(pf.extension().toLowerCase(Locale.ROOT));
                    return new ScoredPF(pf, shortScore, longScore, preferred);
                })
                .filter(sc -> sc.shortScore() != Integer.MAX_VALUE || sc.longScore() != Integer.MAX_VALUE)
                .toList();

        int bestShortScore =
                scoredCandidates.stream().mapToInt(ScoredPF::shortScore).min().orElse(Integer.MAX_VALUE);
        int shortThreshold = bestShortScore == Integer.MAX_VALUE ? Integer.MAX_VALUE : bestShortScore + SHORT_TOLERANCE;

        Comparator<ScoredPF> cmp = Comparator.<ScoredPF>comparingInt(
                        sc -> Math.min(sc.shortScore(), sc.longScore())
                                + (sc.preferred() ? -PREFERRED_EXTENSION_PRIORITY_BONUS : 0))
                .thenComparing(sc -> extractShort.apply(sc.pf()));

        return scoredCandidates.stream()
                .filter(sc -> (sc.shortScore() <= shortThreshold) || (sc.longScore() < bestShortScore))
                .sorted(cmp)
                .limit(100)
                .map(sc -> toCompletion.apply(sc.pf()))
                .toList();
    }
}
