package io.github.jbellis.brokk.analyzer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scala.Tuple2;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MultiAnalyzer implements IAnalyzer {
    private static final Logger logger = LogManager.getLogger(MultiAnalyzer.class);

    private final Map<Language, IAnalyzer> delegates;

    public MultiAnalyzer(Map<Language, IAnalyzer> delegates) {
        this.delegates = delegates; // Store the live map directly
    }

    private <R> Optional<R> findFirst(Function<IAnalyzer, Optional<R>> extractor) {
        for (var delegate : delegates.values()) {
            try {
                var result = extractor.apply(delegate);
                if (result.isPresent()) {
                    return result;
                }
            } catch (UnsupportedOperationException ignored) {
                // This delegate doesn't support the operation
            }
        }
        return Optional.empty();
    }

    private <K, V> Map<K, List<V>> mergeMapsFromCpgAnalyzers(Function<IAnalyzer, Map<K, List<V>>> extractor) {
        return delegates.values().stream()
                .filter(IAnalyzer::isCpg)
                .flatMap(analyzer -> extractor.apply(analyzer).entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey,
                                          Map.Entry::getValue,
                                          (list1, list2) -> Stream.concat(list1.stream(), list2.stream())
                                                                  .distinct()
                                                                  .collect(Collectors.toList())));
    }

    @Override
    public boolean isEmpty() {
        return delegates.values().stream().allMatch(IAnalyzer::isEmpty);
    }

    @Override
    public boolean isCpg() {
        return delegates.values().stream().anyMatch(IAnalyzer::isCpg);
    }

    @Override
    public List<CodeUnit> getUses(String fqName) {
        return delegates.values().stream()
                .filter(IAnalyzer::isCpg)
                .flatMap(analyzer1 -> ((Function<IAnalyzer, List<CodeUnit>>) analyzer -> analyzer.getUses(fqName)).apply(analyzer1).stream())
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public List<Tuple2<CodeUnit, Double>> getPagerank(Map<String, Double> seedClassWeights, int k, boolean reversed) {
        if (seedClassWeights.isEmpty()) {
            logger.warn("MultiAnalyzer pagerank called with empty seed classes -- sub analyzer will be ~random");
        }

        // Assume that the seeds belong to a single CPG, so we look for a matching sub-Analyzer
        for (var analyzer : delegates.values()) {
            if (!analyzer.isCpg()) {
                continue;
            }

            boolean meetsSeedCriteria = false;
            if (seedClassWeights.isEmpty()) {
                meetsSeedCriteria = true; // No seeds to check, so criteria met.
            } else {
                // Check if at least one seed FQ name has a definition in this analyzer.
                for (String fqName : seedClassWeights.keySet()) {
                    Optional<CodeUnit> definition = analyzer.getDefinition(fqName);
                    if (definition.isPresent()) {
                        meetsSeedCriteria = true;
                        break;
                    }
                }
            }

            if (meetsSeedCriteria) {
                return analyzer.getPagerank(seedClassWeights, k, reversed);
            }
        }
        return List.of(); // No suitable analyzer found
    }

    @Override
    public Map<String, List<CallSite>> getCallgraphTo(String methodName, int depth) {
        return mergeMapsFromCpgAnalyzers(analyzer -> analyzer.getCallgraphTo(methodName, depth));
    }

    @Override
    public Map<String, List<CallSite>> getCallgraphFrom(String methodName, int depth) {
        return mergeMapsFromCpgAnalyzers(analyzer -> analyzer.getCallgraphFrom(methodName, depth));
    }

    @Override
    public Optional<String> getSkeleton(String fqName) {
        return findFirst(analyzer -> analyzer.getSkeleton(fqName));
    }

    @Override
    public Optional<String> getSkeletonHeader(String className) {
        return findFirst(analyzer -> analyzer.getSkeletonHeader(className));
    }

    @Override
    public Optional<String> getMethodSource(String fqName) {
        return findFirst(analyzer -> analyzer.getMethodSource(fqName));
    }

    @Override
    public String getClassSource(String fqcn) {
        for (var delegate : delegates.values()) {
            try {
                var source = delegate.getClassSource(fqcn);
                if (source != null && !source.isEmpty()) {
                    return source;
                }
            } catch (SymbolNotFoundException e) {
                // pass
            }
        }
        throw new SymbolNotFoundException("Class source not found for " + fqcn + " in any analyzer.");
    }

    @Override
    public Map<CodeUnit, String> getSkeletons(ProjectFile file) {
        var lang = Language.fromExtension(com.google.common.io.Files.getFileExtension(file.absPath().toString()));
        var delegate = delegates.get(lang);
        if (delegate != null) {
            return delegate.getSkeletons(file);
        }
        return Map.of();
    }

    @Override
    public List<CodeUnit> getMembersInClass(String fqClass) {
        return delegates.values().stream()
                .flatMap(analyzer -> analyzer.getMembersInClass(fqClass).stream())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    @Override
    public List<CodeUnit> getAllDeclarations() {
        return delegates.values().stream()
                .flatMap(analyzer -> analyzer.getAllDeclarations().stream())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    @Override
    public Set<CodeUnit> getDeclarationsInFile(ProjectFile file) {
        var lang = Language.fromExtension(com.google.common.io.Files.getFileExtension(file.absPath().toString()));
        var delegate = delegates.get(lang);
        if (delegate != null) {
            return delegate.getDeclarationsInFile(file);
        }
        return Set.of();
    }

    @Override
    public Optional<ProjectFile> getFileFor(String fqName) {
        return findFirst(analyzer -> analyzer.getFileFor(fqName));
    }

    @Override
    public Optional<CodeUnit> getDefinition(String fqName) {
        return findFirst(analyzer -> analyzer.getDefinition(fqName));
    }

    @Override
    public List<CodeUnit> searchDefinitions(String pattern) {
        return delegates.values().stream()
                .flatMap(analyzer -> analyzer.searchDefinitions(pattern).stream())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    @Override
    public Set<String> getSymbols(Set<CodeUnit> sources) {
        return delegates.values().stream()
                .flatMap(analyzer -> {
                    try {
                        return analyzer.getSymbols(sources).stream();
                    } catch (UnsupportedOperationException e) {
                        return Stream.empty();
                    }
                })
                .collect(Collectors.toSet());
    }

    @Override
    public void update(Set<ProjectFile> changedFiles) {
        for (var an: delegates.values()) {
            an.update(changedFiles);
        }
    }

    @Override
    public FunctionLocation getFunctionLocation(String fqMethodName, List<String> paramNames) {
        // TODO -- unused right now
        throw new UnsupportedOperationException();
    }
}
