package ai.brokk;

import static java.util.Objects.requireNonNull;

import ai.brokk.analyzer.*;
import ai.brokk.analyzer.usages.UsageHit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * String-based convenience methods for analyzer capabilities.
 * If you already have a CodeUnit, call the provider directly.
 */
public class AnalyzerUtil {
    private static final Logger logger = LogManager.getLogger(AnalyzerUtil.class);
    private static final List<Double> SAMPLE_USAGE_PERCENTILES = List.of(0.10, 0.20, 0.30);

    public static List<CodeWithSource> processUsages(IAnalyzer analyzer, List<CodeUnit> uses) {
        List<CodeWithSource> results = new ArrayList<>();

        var methodUses = uses.stream().filter(CodeUnit::isFunction).sorted().toList();
        for (var cu : methodUses) {
            var source = analyzer.getSource(cu, true);
            if (source.isPresent()) {
                results.add(new CodeWithSource(source.get(), cu));
            }
        }

        var classUses = uses.stream().filter(CodeUnit::isClass).sorted().toList();
        for (var cu : classUses) {
            var headerOpt = analyzer.getSkeletonHeader(cu);
            if (headerOpt.isPresent()) {
                results.add(new CodeWithSource(headerOpt.get(), cu));
            }
        }

        var fieldUses = uses.stream().filter(CodeUnit::isField).sorted().toList();
        for (var field : fieldUses) {
            analyzer.parentOf(field)
                    .flatMap(analyzer::getSkeletonHeader)
                    .ifPresent(header -> results.add(new CodeWithSource(header, field)));
        }

        return results;
    }

    public static List<CodeWithSource> sampleUsages(IAnalyzer analyzer, List<UsageHit> hits) {
        if (hits.isEmpty()) {
            return List.of();
        }

        record UsageRepresentative(CodeWithSource codeWithSource, int hitCount, double confidence, int firstIndex) {}

        record UsageCandidate(
                CodeUnit enclosing, CodeUnit owner, double confidence, int firstIndex, CodeWithSource codeWithSource) {}

        Map<CodeUnit, Integer> ownerHitCounts = new LinkedHashMap<>();
        Map<CodeUnit, CodeUnit> ownerByEnclosing = new LinkedHashMap<>();
        Set<CodeUnit> distinctEnclosing = new LinkedHashSet<>();

        for (var hit : hits) {
            var enclosing = hit.enclosing();
            distinctEnclosing.add(enclosing);
            var owner = ownerByEnclosing.computeIfAbsent(
                    enclosing, cu -> analyzer.parentOf(cu).orElse(cu));
            ownerHitCounts.merge(owner, 1, Integer::sum);
        }

        Map<CodeUnit, CodeWithSource> renderByEnclosing = new LinkedHashMap<>();
        for (var enclosing : distinctEnclosing) {
            processUsages(analyzer, List.of(enclosing)).stream()
                    .filter(rendered -> !rendered.code().isBlank())
                    .findFirst()
                    .ifPresent(rendered -> renderByEnclosing.put(enclosing, rendered));
        }

        Map<CodeUnit, List<UsageCandidate>> candidatesByOwner = new LinkedHashMap<>();
        for (int i = 0; i < hits.size(); i++) {
            var hit = hits.get(i);
            var enclosing = hit.enclosing();
            var rendered = renderByEnclosing.get(enclosing);
            if (rendered == null) {
                continue;
            }

            var owner = requireNonNull(ownerByEnclosing.get(enclosing));
            candidatesByOwner
                    .computeIfAbsent(owner, ignored -> new ArrayList<>())
                    .add(new UsageCandidate(enclosing, owner, hit.confidence(), i, rendered));
        }

        Comparator<UsageCandidate> candidateComparator = Comparator.comparingDouble(UsageCandidate::confidence)
                .reversed()
                .thenComparing(c -> ownerHitCounts.getOrDefault(c.owner(), 0), Comparator.reverseOrder())
                .thenComparingInt(c -> c.codeWithSource().code().length())
                .thenComparingInt(UsageCandidate::firstIndex)
                .thenComparing(c -> c.enclosing().fqName(), String.CASE_INSENSITIVE_ORDER);

        List<UsageRepresentative> representatives = candidatesByOwner.entrySet().stream()
                .map(entry -> entry.getValue().stream()
                        .sorted(candidateComparator)
                        .findFirst()
                        .map(best -> new UsageRepresentative(
                                best.codeWithSource(),
                                ownerHitCounts.getOrDefault(entry.getKey(), 0),
                                best.confidence(),
                                best.firstIndex())))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparingInt((UsageRepresentative r) ->
                                r.codeWithSource().code().length())
                        .thenComparingInt(UsageRepresentative::firstIndex)
                        .thenComparing(UsageRepresentative::confidence, Comparator.reverseOrder())
                        .thenComparing(UsageRepresentative::hitCount, Comparator.reverseOrder())
                        .thenComparing(r -> r.codeWithSource().source().fqName(), String.CASE_INSENSITIVE_ORDER))
                .toList();

        if (representatives.size() <= 3) {
            return representatives.stream()
                    .map(UsageRepresentative::codeWithSource)
                    .toList();
        }

        boolean[] used = new boolean[representatives.size()];
        List<CodeWithSource> sampled = new ArrayList<>(3);
        for (double percentile : SAMPLE_USAGE_PERCENTILES) {
            int preferredIndex = (int) Math.floor((representatives.size() - 1) * percentile);
            int index = nextUnusedIndex(used, preferredIndex);
            if (index < 0) {
                index = previousUnusedIndex(used, preferredIndex);
            }
            if (index < 0) {
                continue;
            }
            used[index] = true;
            sampled.add(representatives.get(index).codeWithSource());
        }

        return sampled;
    }

    private static int nextUnusedIndex(boolean[] used, int startIndex) {
        for (int i = Math.max(0, startIndex); i < used.length; i++) {
            if (!used[i]) {
                return i;
            }
        }
        return -1;
    }

    private static int previousUnusedIndex(boolean[] used, int startIndex) {
        for (int i = Math.min(startIndex, used.length - 1); i >= 0; i--) {
            if (!used[i]) {
                return i;
            }
        }
        return -1;
    }

    public static Set<CodeUnit> coalesceInnerClasses(Set<CodeUnit> classes) {
        return classes.stream()
                .filter(cu -> {
                    var name = cu.fqName();
                    if (!name.contains("$")) return true;
                    var parent = name.substring(0, name.indexOf('$'));
                    return classes.stream().noneMatch(other -> other.fqName().equals(parent));
                })
                .collect(Collectors.toSet());
    }

    public static Set<CodeUnit> coalesceNestedUnits(IAnalyzer analyzer, Set<CodeUnit> units) {
        return units.stream()
                .filter(cu -> {
                    Optional<CodeUnit> parentOpt = analyzer.parentOf(cu);
                    if (parentOpt.isEmpty()) {
                        return true;
                    }
                    CodeUnit parent = parentOpt.get();
                    return parent.equals(cu) || !units.contains(parent);
                })
                .collect(Collectors.toSet());
    }

    public static Stream<CodeUnit> getTestDeclarationsWithLogging(IAnalyzer analyzer, Collection<ProjectFile> files) {
        return getTestDeclarationsWithLogging(analyzer, files, true);
    }

    public static Stream<CodeUnit> getTestDeclarationsWithLogging(
            IAnalyzer analyzer, Collection<ProjectFile> files, boolean topLevelDeclOnly) {
        Set<ProjectFile> analyzedFiles = analyzer.getAnalyzedFiles();
        return files.stream().flatMap(testFile -> {
            if (!analyzedFiles.contains(testFile)) {
                logger.warn("Test file is missing from analyzer index: {}", testFile);
                return Stream.empty();
            }

            Set<CodeUnit> decls = topLevelDeclOnly
                    ? Set.copyOf(analyzer.getTopLevelDeclarations(testFile))
                    : analyzer.getDeclarations(testFile);
            if (decls.isEmpty()) {
                logger.warn("Test file contains no code units: {}", testFile);
            }
            return decls.stream();
        });
    }

    private record StackEntry(String method, int depth) {}

    public static String formatCallGraph(
            Map<String, List<CallSite>> callgraph, String rootMethodName, boolean isCallerGraph) {
        var result = new StringBuilder();
        String arrow = isCallerGraph ? "<-" : "->";

        var visited = new HashSet<String>();
        var stack = new ArrayDeque<>(List.of(new StackEntry(rootMethodName, 0)));

        result.append(rootMethodName).append("\n");
        while (!stack.isEmpty()) {
            var entry = stack.pop();
            var sites = callgraph.get(entry.method);
            if (sites == null) {
                continue;
            }
            sites.stream().sorted().forEach(site -> {
                result.append("""
            %s %s
            ```
            %s
            ```
            """
                        .indent(2 * entry.depth)
                        .formatted(arrow, site.target().fqName(), site.sourceLine()));

                if (visited.add(site.target().fqName())) {
                    stack.push(new StackEntry(site.target().fqName(), entry.depth + 1));
                }
            });
        }

        return result.toString();
    }

    public static Optional<String> getSkeleton(IAnalyzer analyzer, String fqName) {
        String combined = analyzer.getDefinitions(fqName).stream()
                .map(analyzer::getSkeleton)
                .flatMap(Optional::stream)
                .collect(Collectors.joining("\n\n"));
        return combined.isEmpty() ? Optional.empty() : Optional.of(combined);
    }

    public static Optional<String> getSkeletonHeader(IAnalyzer analyzer, String className) {
        String combined = analyzer.getDefinitions(className).stream()
                .map(analyzer::getSkeletonHeader)
                .flatMap(Optional::stream)
                .collect(Collectors.joining("\n\n"));
        return combined.isEmpty() ? Optional.empty() : Optional.of(combined);
    }

    public static Optional<String> getSource(IAnalyzer analyzer, String fqName, boolean includeComments) {
        return analyzer.getDefinitions(fqName).stream()
                .filter(cu -> cu.isFunction() || cu.isClass())
                .flatMap(cu -> analyzer.getSource(cu, includeComments).stream())
                .reduce((srcA, srcB) -> srcA + "\n\n" + srcB);
    }

    public static List<CodeUnit> getMembersInClass(IAnalyzer analyzer, String fqClass) {
        return analyzer.getDefinitions(fqClass).stream()
                .filter(CodeUnit::isClass)
                .map(analyzer::getMembersInClass)
                .flatMap(List::stream)
                .toList();
    }

    public static Optional<ProjectFile> getFileFor(IAnalyzer analyzer, String fqName) {
        return analyzer.getDefinitions(fqName).stream().findFirst().map(CodeUnit::source);
    }

    public static Optional<String> extractCallReceiver(IAnalyzer analyzer, String reference) {
        return analyzer.extractCallReceiver(reference);
    }

    public record CodeWithSource(String code, CodeUnit source) {
        public String text(IAnalyzer analyzer) {
            return text(analyzer, List.of(this));
        }

        public static String text(IAnalyzer analyzer, List<CodeWithSource> parts) {
            Map<CodeUnit, List<String>> methodsByOwner = new LinkedHashMap<>();
            List<CodeWithSource> classParts = new ArrayList<>();

            for (var cws : parts) {
                var cu = cws.source();
                if (cu.isFunction()) {
                    var owner = analyzer.parentOf(cu).orElse(cu);
                    methodsByOwner
                            .computeIfAbsent(owner, k -> new ArrayList<>())
                            .add(cws.code());
                } else if (cu.isClass()) {
                    classParts.add(cws);
                }
            }

            StringBuilder sb = new StringBuilder();

            if (!methodsByOwner.isEmpty()) {
                for (var entry : methodsByOwner.entrySet()) {
                    var owner = entry.getKey();
                    sb.append(
                            """
                            <methods class="%s" file="%s">
                            %s
                            </methods>
                            """
                                    .formatted(
                                            owner.fqName(),
                                            owner.source().toString(),
                                            String.join("\n\n", entry.getValue())));
                }
            }

            if (!classParts.isEmpty()) {
                Map<CodeUnit, List<String>> classCodesByCu = new LinkedHashMap<>();
                for (var cws : classParts) {
                    var cu = cws.source();
                    if (!cu.isClass()) continue;
                    classCodesByCu.computeIfAbsent(cu, k -> new ArrayList<>()).add(cws.code());
                }

                for (var entry : classCodesByCu.entrySet()) {
                    var cls = entry.getKey();
                    var codesForClass = entry.getValue();

                    sb.append(
                            """
                            <class file="%s">
                            %s
                            </class>
                            """
                                    .formatted(cls.source().toString(), String.join("\n\n", codesForClass)));
                }
            }

            return sb.toString();
        }
    }
}
