package io.github.jbellis.brokk.analyzer;

import com.google.common.io.Files;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class MultiAnalyzer
        implements IAnalyzer,
                CallGraphProvider,
                UsagesProvider,
                SkeletonProvider,
                SourceCodeProvider,
                IncrementalUpdateProvider {
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

    private <K, V> Map<K, List<V>> mergeMapsFromAnalyzers(Function<IAnalyzer, Map<K, List<V>>> extractor) {
        return delegates.values().stream()
                .flatMap(analyzer -> extractor.apply(analyzer).entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (list1, list2) -> Stream.concat(
                                list1.stream(), list2.stream())
                        .distinct()
                        .collect(Collectors.toList())));
    }

    @Override
    public boolean isEmpty() {
        return delegates.values().stream().allMatch(IAnalyzer::isEmpty);
    }

    @Override
    public List<CodeUnit> getUses(String fqName) {
        return delegates.values().stream()
                .flatMap(
                        analyzer1 -> analyzer1
                                .as(UsagesProvider.class)
                                .map(up -> up.getUses(fqName))
                                .orElse(Collections.emptyList())
                                .stream())
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public List<CodeUnitRelevance> getRelevantCodeUnits(Map<String, Double> seedClassWeights, int k, boolean reversed) {
        if (seedClassWeights.isEmpty()) {
            logger.warn("MultiAnalyzer pagerank called with empty seed classes -- sub analyzer will be ~random");
        }

        for (var analyzer : delegates.values()) {
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
                return analyzer.getRelevantCodeUnits(seedClassWeights, k, reversed);
            }
        }
        return List.of(); // No suitable analyzer found
    }

    @Override
    public Map<String, List<CallSite>> getCallgraphTo(String methodName, int depth) {
        return mergeMapsFromAnalyzers(analyzer -> analyzer.as(CallGraphProvider.class)
                .map(cgp -> cgp.getCallgraphTo(methodName, depth))
                .orElse(Collections.emptyMap()));
    }

    @Override
    public Map<String, List<CallSite>> getCallgraphFrom(String methodName, int depth) {
        return mergeMapsFromAnalyzers(analyzer -> analyzer.as(CallGraphProvider.class)
                .map(cgp -> cgp.getCallgraphFrom(methodName, depth))
                .orElse(Collections.emptyMap()));
    }

    @Override
    public Optional<String> getSkeleton(String fqName) {
        return findFirst(analyzer -> analyzer.as(SkeletonProvider.class).flatMap(skp -> skp.getSkeleton(fqName)));
    }

    @Override
    public Optional<String> getSkeletonHeader(String className) {
        return findFirst(
                analyzer -> analyzer.as(SkeletonProvider.class).flatMap(skp -> skp.getSkeletonHeader(className)));
    }

    @Override
    public Optional<String> getMethodSource(String fqName) {
        return findFirst(analyzer -> analyzer.as(SourceCodeProvider.class).flatMap(scp -> scp.getMethodSource(fqName)));
    }

    @Override
    public Optional<String> getClassSource(String fqcn) {
        for (var delegate : delegates.values()) {
            try {
                final var maybeSource = delegate.as(SourceCodeProvider.class).flatMap(scp -> scp.getClassSource(fqcn));
                if (maybeSource.isPresent()) {
                    return maybeSource;
                }
            } catch (SymbolNotFoundException e) {
                // pass
            }
        }
        throw new SymbolNotFoundException("Class source not found for " + fqcn + " in any analyzer.");
    }

    @Override
    public Map<CodeUnit, String> getSkeletons(ProjectFile file) {
        var lang = Language.fromExtension(Files.getFileExtension(file.absPath().toString()));
        var delegate = delegates.get(lang);
        if (delegate == null) {
            return Collections.emptyMap();
        } else {
            return delegate.as(SkeletonProvider.class)
                    .map(sk -> sk.getSkeletons(file))
                    .orElse(Collections.emptyMap());
        }
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
        var lang = Language.fromExtension(
                com.google.common.io.Files.getFileExtension(file.absPath().toString()));
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
    public IAnalyzer update() {
        for (var an : delegates.values()) {
            an.as(IncrementalUpdateProvider.class).ifPresent(IncrementalUpdateProvider::update);
        }
        return this;
    }

    @Override
    public IAnalyzer update(Set<ProjectFile> changedFiles) {
        for (var an : delegates.values()) {
            an.as(IncrementalUpdateProvider.class).ifPresent(incAnalyzer -> incAnalyzer.update(changedFiles));
        }
        return this;
    }

    @Override
    public FunctionLocation getFunctionLocation(String fqMethodName, List<String> paramNames) {
        // TODO -- unused right now
        throw new UnsupportedOperationException();
    }

    /** @return a copy of the delegates of this analyzer. */
    public Map<Language, IAnalyzer> getDelegates() {
        return Collections.unmodifiableMap(delegates);
    }
}
