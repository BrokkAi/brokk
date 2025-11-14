package ai.brokk.analyzer;

import ai.brokk.IProject;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MultiAnalyzer
        implements IAnalyzer, CallGraphProvider, SkeletonProvider, SourceCodeProvider, TypeAliasProvider {
    private static final Logger log = LoggerFactory.getLogger(MultiAnalyzer.class);
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

    /**
     * Get the delegate analyzer for the language of the given CodeUnit.
     *
     * @param cu The CodeUnit whose language to detect
     * @return The delegate analyzer for that language, or empty if no delegate exists
     */
    private Optional<IAnalyzer> delegateFor(CodeUnit cu) {
        var lang = Languages.fromExtension(cu.source().extension());
        var delegate = delegates.get(lang);
        if (delegate == null) {
            log.debug("No delegate found for language {} (from file {})", lang, cu.source());
        }
        return Optional.ofNullable(delegate);
    }

    /**
     * Get the delegate analyzer for the language of the given ProjectFile.
     *
     * @param file The ProjectFile whose language to detect
     * @return The delegate analyzer for that language, or empty if no delegate exists
     */
    private Optional<IAnalyzer> delegateFor(ProjectFile file) {
        var lang = Languages.fromExtension(file.extension());
        var delegate = delegates.get(lang);
        if (delegate == null) {
            log.debug("No delegate found for language {} (from file {})", lang, file);
        }
        return Optional.ofNullable(delegate);
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
    public List<String> importStatementsOf(ProjectFile file) {
        return delegates.values().stream()
                .flatMap(analyzer -> analyzer.importStatementsOf(file).stream())
                .toList();
    }

    @Override
    public Optional<CodeUnit> enclosingCodeUnit(ProjectFile file, Range range) {
        return delegates.values().stream()
                .flatMap(analyzer -> analyzer.enclosingCodeUnit(file, range).stream())
                .findFirst();
    }

    @Override
    public IProject getProject() {
        return findFirst(analyzer -> Optional.of(analyzer.getProject())).orElseThrow();
    }

    @Override
    public Map<String, List<CallSite>> getCallgraphTo(CodeUnit method, int depth) {
        return delegateFor(method)
                .flatMap(delegate -> delegate.as(CallGraphProvider.class))
                .map(cgp -> cgp.getCallgraphTo(method, depth))
                .orElse(Collections.emptyMap());
    }

    @Override
    public Map<String, List<CallSite>> getCallgraphFrom(CodeUnit method, int depth) {
        return delegateFor(method)
                .flatMap(delegate -> delegate.as(CallGraphProvider.class))
                .map(cgp -> cgp.getCallgraphFrom(method, depth))
                .orElse(Collections.emptyMap());
    }

    @Override
    public Optional<String> getSkeleton(CodeUnit cu) {
        return delegateFor(cu)
                .flatMap(delegate -> delegate.as(SkeletonProvider.class))
                .flatMap(skp -> skp.getSkeleton(cu));
    }

    @Override
    public Optional<String> getSkeletonHeader(CodeUnit classUnit) {
        return delegateFor(classUnit)
                .flatMap(delegate -> delegate.as(SkeletonProvider.class))
                .flatMap(skp -> skp.getSkeletonHeader(classUnit));
    }

    @Override
    public List<CodeUnit> getTopLevelDeclarations(ProjectFile file) {
        return delegateFor(file)
                .map(delegate -> delegate.getTopLevelDeclarations(file))
                .orElse(List.of());
    }

    @Override
    public List<CodeUnit> getDirectChildren(CodeUnit cu) {
        return delegateFor(cu).map(delegate -> delegate.getDirectChildren(cu)).orElse(List.of());
    }

    @Override
    public Set<String> getMethodSources(CodeUnit method, boolean includeComments) {
        return delegateFor(method)
                .flatMap(delegate -> delegate.as(SourceCodeProvider.class))
                .map(scp -> scp.getMethodSources(method, includeComments))
                .orElse(Collections.emptySet());
    }

    @Override
    public Optional<String> getClassSource(CodeUnit classUnit, boolean includeComments) {
        return delegateFor(classUnit)
                .flatMap(delegate -> delegate.as(SourceCodeProvider.class))
                .flatMap(scp -> scp.getClassSource(classUnit, includeComments));
    }

    @Override
    public Optional<String> getSourceForCodeUnit(CodeUnit codeUnit, boolean includeComments) {
        return findFirst(analyzer -> analyzer.as(SourceCodeProvider.class)
                .flatMap(scp -> scp.getSourceForCodeUnit(codeUnit, includeComments)));
    }

    @Override
    public Map<CodeUnit, String> getSkeletons(ProjectFile file) {
        return delegateFor(file)
                .flatMap(delegate -> delegate.as(SkeletonProvider.class))
                .map(sk -> sk.getSkeletons(file))
                .orElse(Collections.emptyMap());
    }

    @Override
    public List<CodeUnit> getMembersInClass(CodeUnit classUnit) {
        return delegateFor(classUnit)
                .map(delegate -> delegate.getMembersInClass(classUnit))
                .orElse(List.of());
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
    public Set<CodeUnit> getDeclarations(ProjectFile file) {
        return delegateFor(file).map(delegate -> delegate.getDeclarations(file)).orElse(Set.of());
    }

    @Override
    public Optional<CodeUnit> getDefinition(String fqName) {
        return findFirst(analyzer -> analyzer.getDefinition(fqName));
    }

    @Override
    public Set<CodeUnit> searchDefinitions(String pattern) {
        return delegates.values().stream()
                .flatMap(analyzer -> analyzer.searchDefinitions(pattern).stream())
                .collect(Collectors.toSet());
    }

    @Override
    public Set<CodeUnit> autocompleteDefinitions(String query) {
        return delegates.values().stream()
                .flatMap(analyzer -> analyzer.autocompleteDefinitions(query).stream())
                .collect(Collectors.toSet());
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
        final var newDelegates = new HashMap<Language, IAnalyzer>(delegates.size());
        for (var entry : delegates.entrySet()) {
            var delegateKey = entry.getKey();
            var analyzer = entry.getValue();
            newDelegates.put(delegateKey, analyzer.update());
        }
        return new MultiAnalyzer(newDelegates);
    }

    @Override
    public IAnalyzer update(Set<ProjectFile> changedFiles) {
        final var newDelegates = new HashMap<Language, IAnalyzer>(delegates.size());
        for (var entry : delegates.entrySet()) {
            var delegateKey = entry.getKey();
            var analyzer = entry.getValue();

            // Filter files by language extensions
            var languageExtensions = delegateKey.getExtensions();
            var relevantFiles = changedFiles.stream()
                    .filter(pf -> languageExtensions.contains(pf.extension()))
                    .collect(Collectors.toSet());

            if (relevantFiles.isEmpty()) {
                newDelegates.put(delegateKey, analyzer);
            } else {
                newDelegates.put(delegateKey, analyzer.update(relevantFiles));
            }
        }

        return new MultiAnalyzer(newDelegates);
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
