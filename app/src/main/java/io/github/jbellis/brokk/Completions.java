package io.github.jbellis.brokk;

import io.github.jbellis.brokk.analyzer.*;
import org.fife.ui.autocomplete.ShorthandCompletion;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

public class Completions {
    public static List<CodeUnit> completeSymbols(String input, IAnalyzer analyzer) {
        String pattern = input.trim();
        var allDefs = analyzer.getAllDeclarations().stream().toList();

        // empty pattern -> alphabetic list
        if (pattern.isEmpty()) {
            return allDefs.stream()
                    .distinct() // collapse method overloads
                    .sorted(Comparator.comparing(CodeUnit::fqName))
                    .toList();
        }

        var matcher = new FuzzyMatcher(pattern);
        boolean hierarchicalQuery = pattern.indexOf('.') >= 0 || pattern.indexOf('$') >= 0;

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
                .map(ScoredCU::cu)
                .toList();
    }

    /**
     * Expand paths that may contain wildcards (*, ?), returning all matches.
     */
    public static List<BrokkFile> expandPath(IProject project, String pattern) {
        // First check if this is a single file
        var file = maybeExternalFile(project.getRoot(), pattern);
        if (file.exists()) {
            return List.of(file);
        }

        // Handle glob patterns [only in the last part of the path]
        if (pattern.contains("*") || pattern.contains("?")) {
            var parent = file.absPath().getParent();
            while (parent != null && (parent.getFileName().toString().contains("*") || parent.getFileName().toString().contains("?"))) {
                parent = parent.getParent();
            }
            requireNonNull(parent);
            var matcher = FileSystems.getDefault().getPathMatcher("glob:" + file.absPath());
            try (var stream = Files.walk(parent)) {
                return stream
                        .filter(Files::isRegularFile)
                        .filter(matcher::matches)
                        .map(p -> maybeExternalFile(project.getRoot(), p.toString()))
                        .toList();
            } catch (IOException e) {
                // part of the path doesn't exist
                return List.of();
            }
        }

        // If not a glob and doesn't exist directly, look for matches in git tracked files
        var filename = Path.of(pattern).getFileName().toString();
        var matches = project.getAllFiles().stream()
                .filter(p -> p.getFileName().equals(filename))
                .map(f -> (BrokkFile) f)
                .toList();
        if (matches.size() != 1) {
            return List.of();
        }

        return matches;
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

    private record ScoredItem<T>(T source, int score, int tiebreakScore, boolean isShort) { // Renamed to avoid conflict
    }

    public static <T> List<ShorthandCompletion> scoreShortAndLong(String pattern,
                                                                  Collection<T> candidates,
                                                                  Function<T, String> extractShort,
                                                                  Function<T, String> extractLong,
                                                                  Function<T, Integer> tiebreaker,
                                                                  Function<T, ShorthandCompletion> toCompletion)
    {
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
