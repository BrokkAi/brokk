package io.github.jbellis.brokk.analyzer;

import com.google.common.io.Files;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MultiAnalyzer
        implements IAnalyzer,
                CallGraphProvider,
                UsagesProvider,
                SkeletonProvider,
                SourceCodeProvider,
                TypeAliasProvider {
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
    public Set<Language> languages() {
        return delegates.keySet();
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
    public List<CodeUnit> topLevelCodeUnitsOf(ProjectFile file) {
        var lang = Languages.fromExtension(Files.getFileExtension(file.absPath().toString()));
        var delegate = delegates.get(lang);
        if (delegate != null) {
            return delegate.topLevelCodeUnitsOf(file);
        }
        return List.of();
    }

    @Override
    public Set<String> getMethodSources(String fqName, boolean includeComments) {
        return findFirst(analyzer -> analyzer.as(SourceCodeProvider.class)
                        .map(scp -> scp.getMethodSources(fqName, includeComments))
                        .filter(sources -> !sources.isEmpty()))
                .orElse(Collections.emptySet());
    }

    @Override
    public Optional<String> getClassSource(String fqcn, boolean includeComments) {
        for (var delegate : delegates.values()) {
            try {
                final var maybeSource =
                        delegate.as(SourceCodeProvider.class).flatMap(scp -> scp.getClassSource(fqcn, includeComments));
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
    public Optional<String> getSourceForCodeUnit(CodeUnit codeUnit, boolean includeComments) {
        return findFirst(analyzer -> analyzer.as(SourceCodeProvider.class)
                .flatMap(scp -> scp.getSourceForCodeUnit(codeUnit, includeComments)));
    }

    @Override
    public Map<CodeUnit, String> getSkeletons(ProjectFile file) {
        var lang = Languages.fromExtension(Files.getFileExtension(file.absPath().toString()));
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
        var lang = Languages.fromExtension(
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
    public boolean isDefinitionAvailable(String fqName) {
        return delegates.values().stream().anyMatch(analyzer -> analyzer.isDefinitionAvailable(fqName));
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
    public List<CodeUnit> autocompleteDefinitions(String query) {
        return delegates.values().stream()
                .flatMap(analyzer -> analyzer.autocompleteDefinitions(query).stream())
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
            an.update();
        }
        return this;
    }

    @Override
    public IAnalyzer update(Set<ProjectFile> changedFiles) {

        for (var entry : delegates.entrySet()) {
            var delegateKey = entry.getKey();
            var analyzer = entry.getValue();

            // Filter files by language extensions
            var languageExtensions = delegateKey.getExtensions();
            var relevantFiles = changedFiles.stream()
                    .filter(pf -> languageExtensions.contains(pf.extension()))
                    .collect(Collectors.toSet());

            if (relevantFiles.isEmpty()) {
                continue;
            }

            analyzer.update(relevantFiles);
        }

        return this;
    }

    @Override
    public Optional<String> extractClassName(String reference) {
        return findFirst(analyzer -> analyzer.extractClassName(reference));
    }

    @Override
    public boolean isTypeAlias(CodeUnit cu) {
        for (var delegate : delegates.values()) {
            try {
                var providerOpt = delegate.as(TypeAliasProvider.class);
                if (providerOpt.isPresent() && providerOpt.get().isTypeAlias(cu)) {
                    return true;
                }
            } catch (UnsupportedOperationException ignored) {
                // delegate doesn't implement capability
            }
        }
        return false;
    }

    /** @return a copy of the delegates of this analyzer. */
    public Map<Language, IAnalyzer> getDelegates() {
        return Collections.unmodifiableMap(delegates);
    }
}
